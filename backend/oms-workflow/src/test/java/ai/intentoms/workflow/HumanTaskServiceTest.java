package ai.intentoms.workflow;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.workflow.api.TaskController;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import ai.intentoms.workflow.task.TaskQueue;
import ai.intentoms.workflow.task.TaskQueueService;
import ai.intentoms.workflow.task.TaskRequest;
import ai.intentoms.workflow.task.TaskStatus;
import ai.intentoms.workflow.task.TaskSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HumanTaskServiceTest extends WorkflowTestBase {
    @Autowired HumanTaskService tasks;
    @Autowired TaskQueueService queues;
    @Autowired TaskSweeper sweeper;
    @Autowired EventStore events;
    @Autowired TaskController controller;

    @Test
    void lifecycle_create_claim_complete_withLazyQueue() {
        String queue = "q-" + UUID.randomUUID().toString().substring(0, 6);
        HumanTask t = tasks.create(TaskRequest.of("approval", queue, "Approve margin").withSubject("order", "o-1").withPayload(Map.of("margin", 0.08)).withPriority(3));
        assertThat(t.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(t.id()).startsWith("task_");
        assertThat(t.slaDueAt()).isBetween(t.createdAt().plus(Duration.ofMinutes(239)), t.createdAt().plus(Duration.ofMinutes(241)));
        TaskQueue q = queues.get(queue);
        assertThat(q.defaultSlaMinutes()).isEqualTo(240);

        HumanTask claimed = tasks.claim(t.id(), null);
        assertThat(claimed.status()).isEqualTo(TaskStatus.CLAIMED);
        assertThat(claimed.assignee()).isEqualTo("tester");
        assertThatThrownBy(() -> tasks.claim(t.id(), "somebody-else")).isInstanceOf(ConflictException.class);

        HumanTask done = tasks.complete(t.id(), "approved", "margin acceptable", Map.of("note", "ok"));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.outcome()).isEqualTo("approved");
        assertThat(done.completedBy()).isEqualTo("tester");
        assertThatThrownBy(() -> tasks.complete(t.id(), "again", null, null)).isInstanceOf(ConflictException.class);
        assertThat(events.load("task", t.id()).stream().map(Event::type)).containsExactly("task.created", "task.claimed", "task.completed");

        assertThat(tasks.list(new TaskQuery(queue, TaskStatus.COMPLETED, null, null, null, null, 0, 10)).items()).extracting(HumanTask::id).containsExactly(t.id());
        assertThat(tasks.list(new TaskQuery(null, null, null, "order", "o-1", null, 0, 10)).total()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cancelAndPermissions() {
        HumanTask t = tasks.create(TaskRequest.of("review", "cs_review", "Look at this"));
        HumanTask cancelled = tasks.cancel(t.id(), "duplicate");
        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(events.load("task", t.id()).stream().map(Event::type)).containsExactly("task.created", "task.cancelled");

        actAs("viewer", Set.of("task.read"));
        assertThat(controller.get(t.id()).id()).isEqualTo(t.id());
        assertThatThrownBy(() -> controller.claim(t.id(), null)).isInstanceOf(ForbiddenException.class);
        actAs("nobody", Set.of());
        assertThatThrownBy(() -> controller.get(t.id())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sweeperEscalatesOverdueTasks() throws Exception {
        String queue = "esc-" + UUID.randomUUID().toString().substring(0, 6);
        queues.upsert(new TaskQueue(queue, 60, queue + "-leads", Set.of("returns"), "test queue"));
        HumanTask overdue = tasks.create(TaskRequest.of("exception", queue, "Stuck order").withSla(Duration.ofMillis(5)).withSkills(Set.of("returns")));
        HumanTask fresh = tasks.create(TaskRequest.of("exception", queue, "New order"));
        Thread.sleep(20);
        int n = sweeper.sweep();
        assertThat(n).isGreaterThanOrEqualTo(1);
        HumanTask escalated = tasks.get(overdue.id());
        assertThat(escalated.queue()).isEqualTo(queue + "-leads");
        assertThat(escalated.priority()).isEqualTo(10);
        assertThat(escalated.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(escalated.requiredSkills()).containsExactly("returns");
        assertThat(events.load("task", overdue.id()).stream().map(Event::type)).containsExactly("task.created", "task.escalated");
        assertThat(tasks.get(fresh.id()).queue()).isEqualTo(queue);
        assertThat(sweeper.sweep()).as("already escalated tasks are not escalated twice").isZero();
        assertThat(queues.list()).extracting(TaskQueue::name).contains(queue, queue + "-leads");
    }
}
