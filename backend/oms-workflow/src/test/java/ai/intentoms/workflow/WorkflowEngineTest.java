package ai.intentoms.workflow;

import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.engine.WorkflowRegistry;
import ai.intentoms.workflow.engine.WorkflowWorker;
import ai.intentoms.workflow.model.TimelineEntry;
import ai.intentoms.workflow.model.WorkflowEngine;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import ai.intentoms.workflow.task.TaskStatus;
import ai.intentoms.workflow.testflows.SagaWorkflow;
import ai.intentoms.workflow.testflows.TestFlowWorkflow;
import ai.intentoms.workflow.testflows.VersionedWorkflowV1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEngineTest extends WorkflowTestBase {
    @Autowired WorkflowEngine engine;
    @Autowired WorkflowWorker worker;
    @Autowired WorkflowRegistry registry;
    @Autowired HumanTaskService tasks;
    @Autowired EventStore events;
    @Autowired TimeSource time;

    @AfterEach
    void resetClock() { time.reset(); }

    private static String key() { return "ord-" + UUID.randomUUID().toString().substring(0, 8); }

    private void advance(Duration d) { time.override(Clock.offset(Clock.systemUTC(), d)); }

    private List<String> eventTypes(String aggregateType, String id) { return events.load(aggregateType, id).stream().map(Event::type).toList(); }

    @Test
    void startIsIdempotentPerBusinessKey() {
        String key = key();
        String id1 = engine.start(TestFlowWorkflow.TYPE, key, Map.of("amount", 10));
        String id2 = engine.start(TestFlowWorkflow.TYPE, key, Map.of("amount", 99));
        assertThat(id2).isEqualTo(id1);
        WorkflowView v = engine.get(id1);
        assertThat(v.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(v.currentStep()).isEqualTo("begin");
        assertThat(v.vars()).containsEntry("amount", 10);
        assertThat(engine.find(TestFlowWorkflow.TYPE, key)).isPresent();
        assertThat(eventTypes("workflow", id1)).containsExactly("workflow.started");
        Event started = events.load("workflow", id1).get(0);
        assertThat(started.payload()).containsEntry("businessKey", key).containsEntry("type", TestFlowWorkflow.TYPE);
    }

    @Test
    void activityResultsAreCachedAcrossRetries() {
        String key = key();
        String id = engine.start(TestFlowWorkflow.TYPE, key, Map.of());
        // First run: "stable" succeeds, "flaky" fails -> RetryLater with a 1 ms backoff.
        assertThat(worker.runOnce()).isGreaterThanOrEqualTo(1);
        WorkflowView afterFirst = engine.get(id);
        // Either still RUNNING on "begin" (retry pending) or already past it if the backoff elapsed inside runOnce.
        if (afterFirst.currentStep().equals("begin")) {
            assertThat(afterFirst.status()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(afterFirst.lastError()).contains("flaky");
            assertThat(afterFirst.vars()).doesNotContainKey("flaky");
            advance(Duration.ofSeconds(1));
            worker.runOnce();
        }
        WorkflowView v = engine.get(id);
        assertThat(v.status()).isEqualTo(WorkflowStatus.WAITING_SIGNAL);
        assertThat(v.currentStep()).isEqualTo("wait");
        assertThat(TestFlowWorkflow.count(key, "begin_runs")).isEqualTo(2);
        assertThat(TestFlowWorkflow.count(key, "stable_body")).as("cached activity must not re-run").isEqualTo(1);
        assertThat(TestFlowWorkflow.count(key, "flaky_body")).isEqualTo(2);
        assertThat(v.vars()).containsEntry("flaky", "ok-2").containsEntry("stable", Map.of("v", 1));
        assertThat(v.lastError()).isNull();
        List<TimelineEntry> tl = engine.timeline(id);
        assertThat(tl.stream().filter(e -> "activity".equals(e.kind()) && "stable".equals(e.name())).map(TimelineEntry::status)).containsExactly("ok", "replayed");
        assertThat(tl.stream().filter(e -> "activity".equals(e.kind()) && "flaky".equals(e.name())).map(TimelineEntry::status)).containsExactly("retry", "ok");
        assertThat(tl.stream().filter(e -> "step".equals(e.kind()) && "begin".equals(e.name())).map(TimelineEntry::status)).containsExactly("retry", "ok");
    }

    @Test
    void fullLifecycle_signalsInOrderWithDedup_timer_task_timeline() {
        String key = key();
        String id = engine.start(TestFlowWorkflow.TYPE, key, Map.of());
        driveToWait(id);

        // Timeline while waiting ends with the pending timer and the next expected event (FR-WF-06).
        List<TimelineEntry> waiting = engine.timeline(id);
        assertThat(waiting.get(waiting.size() - 1).kind()).isEqualTo("signal");
        assertThat(waiting.get(waiting.size() - 1).status()).isEqualTo("expected");
        assertThat(waiting.get(waiting.size() - 1).details()).containsEntry("onSignal", "on_decision");
        assertThat(waiting.get(waiting.size() - 2).kind()).isEqualTo("timer");
        assertThat(waiting.get(waiting.size() - 2).status()).isEqualTo("pending");

        // Three deliveries, one duplicate: consumed in arrival order, duplicate dropped (FR-WF-05).
        engine.signal(TestFlowWorkflow.TYPE, key, "approved", Map.of("more", true), "wh-1");
        engine.signal(TestFlowWorkflow.TYPE, key, "approved", Map.of("more", true), "wh-1");
        engine.signal(TestFlowWorkflow.TYPE, key, "rejected", Map.of("more", false), "wh-2");
        engine.signalById(id, "unrelated", Map.of(), "wh-3"); // stays queued, never consumed
        assertThat(eventTypes("workflow", id)).filteredOn("workflow.signalled"::equals).hasSize(3);

        worker.runOnce();
        WorkflowView v = engine.get(id);
        assertThat(v.status()).isEqualTo(WorkflowStatus.WAITING_TIMER);
        assertThat(v.currentStep()).isEqualTo("on_decision");
        assertThat(v.vars()).containsEntry("signals", List.of("approved", "rejected"));
        assertThat(v.nextWakeAt()).isAfter(time.now().plus(Duration.ofMinutes(29)));

        // Timer not yet due: the instance stays parked.
        worker.runOnce();
        assertThat(engine.get(id).status()).isEqualTo(WorkflowStatus.WAITING_TIMER);
        advance(Duration.ofMinutes(31));
        worker.runOnce();
        v = engine.get(id);
        assertThat(v.status()).isEqualTo(WorkflowStatus.WAITING_TASK);
        String taskId = (String) v.vars().get("taskId");
        assertThat(taskId).isNotNull();
        HumanTask task = tasks.get(taskId);
        assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(task.workflowId()).isEqualTo(id);
        assertThat(engine.timeline(id).get(engine.timeline(id).size() - 1)).extracting(TimelineEntry::kind, TimelineEntry::status).containsExactly("task", "expected");

        // Completing the task signals "task:<id>" and resumes the workflow (FR-WF-04).
        tasks.claim(taskId, null);
        tasks.complete(taskId, "approve", "looks fine", Map.of("score", 5));
        worker.runOnce();
        v = engine.get(id);
        assertThat(v.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(v.vars()).containsEntry("taskOutcome", "approve");
        assertThat(eventTypes("workflow", id)).startsWith("workflow.started").endsWith("workflow.completed");
        Event completed = events.load("workflow", id).get(events.load("workflow", id).size() - 1);
        assertThat(completed.payload()).containsEntry("result", Map.of("outcome", "approve", "signals", List.of("approved", "rejected")));

        List<String> kinds = engine.timeline(id).stream().map(TimelineEntry::kind).distinct().toList();
        assertThat(kinds).contains("workflow", "step", "activity", "signal", "timer", "task", "log");
        assertThat(engine.timeline(id).stream().filter(e -> "signal".equals(e.kind()) && "consumed".equals(e.status())).map(TimelineEntry::name))
            .containsExactly("approved", "rejected", "task:" + taskId);
        assertThat(engine.timeline(id).stream().filter(e -> "timer".equals(e.kind())).map(TimelineEntry::status)).contains("scheduled", "fired");
        assertThat(tasks.list(new TaskQuery(null, null, null, null, null, id, 0, 10)).total()).isEqualTo(1);
    }

    @Test
    void waitSignalWakesImmediatelyWhenSignalAlreadyQueued() {
        String key = key();
        String id = engine.start(TestFlowWorkflow.TYPE, key, Map.of());
        engine.signalById(id, "rejected", Map.of("more", false), "early");   // arrives before the step waits for it
        driveToWait(id);
        WorkflowView v = engine.get(id);
        assertThat(v.status()).as("queued signal consumed as soon as the wait was declared").isEqualTo(WorkflowStatus.WAITING_TIMER);
        assertThat(v.vars()).containsEntry("signals", List.of("rejected"));
    }

    @Test
    void waitTimeoutRunsOnTimeoutStep() {
        String key = key();
        String id = engine.start(TestFlowWorkflow.TYPE, key, Map.of());
        driveToWait(id);
        advance(Duration.ofHours(2));
        worker.runOnce();
        WorkflowView v = engine.get(id);
        assertThat(v.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(v.lastError()).startsWith("timed out");
        assertThat(eventTypes("workflow", id)).endsWith("workflow.failed");
    }

    @Test
    void compensationsRunInReverseOnFail() {
        String key = key();
        String id = engine.start(SagaWorkflow.TYPE, key, Map.of());
        worker.runOnce();
        WorkflowView v = engine.get(id);
        assertThat(v.status()).isEqualTo(WorkflowStatus.COMPENSATED);
        assertThat(v.lastError()).contains("no node can fulfil");
        List<String> mine = SagaWorkflow.UNDONE.stream().filter(s -> s.contains(key)).toList();
        // in-process undo first (same execution), then durable handlers newest-first with the vars snapshot taken at registration.
        assertThat(mine).containsExactly("notify_only:" + key, "void_auth:auth-" + key, "release_reservation:res-" + key);
        assertThat(eventTypes("workflow", id)).containsExactly("workflow.started", "workflow.failed", "workflow.compensated");
        List<TimelineEntry> comp = engine.timeline(id).stream().filter(e -> "compensation".equals(e.kind())).toList();
        assertThat(comp.stream().map(TimelineEntry::status)).contains("registered", "started", "ok");
        assertThat(comp.stream().filter(e -> "ok".equals(e.status())).map(TimelineEntry::name)).containsExactly("notify_only", "void_auth", "release_reservation");
    }

    @Test
    void instancesStayPinnedToTheirVersion() {
        String keyV1 = key();
        String id1 = engine.start(VersionedWorkflowV1.TYPE, keyV1, Map.of());
        worker.runOnce();
        assertThat(engine.get(id1).status()).isEqualTo(WorkflowStatus.WAITING_TIMER);
        assertThat(engine.get(id1).version()).isEqualTo(1);

        registry.register(new VersionedWorkflowV1.V2());      // "deploy" v2 while v1 is in flight
        String id2 = engine.start(VersionedWorkflowV1.TYPE, key(), Map.of());
        assertThat(engine.get(id2).version()).isEqualTo(2);
        assertThat(registry.latest(VersionedWorkflowV1.TYPE).version()).isEqualTo(2);
        worker.runOnce();                                       // v2 instance reaches its own timer
        assertThat(engine.get(id2).status()).isEqualTo(WorkflowStatus.WAITING_TIMER);

        advance(Duration.ofHours(2));
        worker.runOnce();
        assertThat(engine.get(id1).status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(engine.get(id1).version()).isEqualTo(1);
        assertThat(engine.get(id1).vars()).containsEntry("ranVersion", 1);
        assertThat(engine.get(id2).status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(engine.get(id2).vars()).containsEntry("ranVersion", 2);
    }

    @Test
    void cancelAndRetryNow() {
        String id = engine.start(TestFlowWorkflow.TYPE, key(), Map.of());
        driveToWait(id);
        engine.cancel(id, "customer changed mind");
        WorkflowView v = engine.get(id);
        assertThat(v.status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(eventTypes("workflow", id)).endsWith("workflow.cancelled");
        worker.runOnce();
        assertThat(engine.get(id).status()).isEqualTo(WorkflowStatus.CANCELLED);

        String id2 = engine.start(TestFlowWorkflow.TYPE, key(), Map.of());
        driveToWait(id2);
        advance(Duration.ofHours(2));
        worker.runOnce();
        assertThat(engine.get(id2).status()).isEqualTo(WorkflowStatus.FAILED);
        time.reset();
        engine.retryNow(id2);                                   // ops: re-run the step that was current when it failed ("wait")
        assertThat(engine.get(id2).status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(engine.get(id2).currentStep()).isEqualTo("wait");
        worker.runOnce();
        WorkflowView retried = engine.get(id2);
        assertThat(retried.status()).as("re-running the waiting step re-arms the wait").isEqualTo(WorkflowStatus.WAITING_SIGNAL);
        assertThat(retried.lastError()).isNull();
        assertThat(retried.nextWakeAt()).isAfter(time.now().plus(Duration.ofMinutes(59)));
        assertThat(engine.timeline(id2).stream().filter(e -> "log".equals(e.kind()) && "retryNow".equals(e.name()))).hasSize(1);
    }

    /** Run until the flaky activity has recovered and the instance waits for a decision signal. */
    private void driveToWait(String id) {
        worker.runOnce();
        if (!WorkflowStatus.WAITING_SIGNAL.equals(engine.get(id).status()) && "begin".equals(engine.get(id).currentStep())) {
            advance(Duration.ofSeconds(1));
            worker.runOnce();
            time.reset();
        }
        WorkflowView v = engine.get(id);
        assertThat(v.currentStep()).isNotEqualTo("begin");
    }
}
