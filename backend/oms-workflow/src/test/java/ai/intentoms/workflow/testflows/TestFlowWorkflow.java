package ai.intentoms.workflow.testflows;

import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.RetryPolicy;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowDefinition;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises every {@link Next} variant: a flaky activity (retry + replay), a signal wait consumed in order,
 * a durable timer, a human task wait and completion.
 */
@Component
public class TestFlowWorkflow implements WorkflowDefinition {
    public static final String TYPE = "test.flow";
    /** Per business key execution counters: "<key>:<counter>" → count. */
    public static final Map<String, AtomicInteger> COUNTERS = new ConcurrentHashMap<>();

    private final HumanTaskService tasks;

    public TestFlowWorkflow(HumanTaskService tasks) { this.tasks = tasks; }

    public static int count(String key, String counter) {
        AtomicInteger c = COUNTERS.get(key + ":" + counter);
        return c == null ? 0 : c.get();
    }

    private static int bump(String key, String counter) {
        return COUNTERS.computeIfAbsent(key + ":" + counter, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override public String type() { return TYPE; }
    @Override public int version() { return 1; }
    @Override public String initialStep() { return "begin"; }

    @Override
    public Map<String, Step> steps() {
        Map<String, Step> s = new LinkedHashMap<>();
        s.put("begin", ctx -> {
            bump(ctx.businessKey(), "begin_runs");
            Map<String, Object> stable = ctx.activity("stable", RetryPolicy.none(), () -> {
                bump(ctx.businessKey(), "stable_body");
                return Map.of("v", 1);
            });
            String flaky = ctx.activity("flaky", RetryPolicy.of(3, Duration.ofMillis(1)), () -> {
                int n = bump(ctx.businessKey(), "flaky_body");
                if (n == 1) throw new IllegalStateException("first attempt always fails");
                return "ok-" + n;
            });
            ctx.vars().put("stable", stable);
            ctx.vars().put("flaky", flaky);
            ctx.log("begin done", Map.of("flaky", flaky));
            return Next.goTo("wait");
        });
        s.put("wait", ctx -> Next.waitSignal(Set.of("approved", "rejected"), Duration.ofHours(1), "timed_out", "on_decision"));
        s.put("on_decision", ctx -> {
            Signal sig = ctx.signal().orElseThrow();
            @SuppressWarnings("unchecked")
            List<String> seen = ctx.vars().get("signals") instanceof List<?> l ? new ArrayList<>((List<String>) l) : new ArrayList<>();
            seen.add(sig.name());
            ctx.vars().put("signals", seen);
            if (Boolean.TRUE.equals(sig.payload().get("more"))) return Next.waitSignal(Set.of("approved", "rejected"), Duration.ofHours(1), "timed_out", "on_decision");
            return Next.sleep(Duration.ofMinutes(30), "after_sleep");
        });
        s.put("after_sleep", ctx -> {
            String taskId = ctx.activity("create_task", RetryPolicy.none(), () -> tasks.create(
                TaskRequest.of("review", "test_reviews", "Review " + ctx.businessKey()).withSubject("order", ctx.businessKey()).withWorkflow(ctx.workflowId())).id());
            ctx.vars().put("taskId", taskId);
            return Next.waitTask(taskId, "task_done", Duration.ofHours(1), "timed_out");
        });
        s.put("task_done", ctx -> {
            Signal sig = ctx.signal().orElseThrow();
            ctx.vars().put("taskOutcome", sig.payload().get("outcome"));
            return Next.complete(Map.of("outcome", String.valueOf(sig.payload().get("outcome")), "signals", ctx.vars().get("signals")));
        });
        s.put("timed_out", ctx -> Next.fail("timed out in " + ctx.vars().getOrDefault("signals", List.of()), false));
        return s;
    }
}
