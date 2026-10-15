package ai.intentoms.workflow.testflows;

import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Two durable compensations plus one in-process undo; the last step fails with compensate = true (FR-WF-02). */
@Component
public class SagaWorkflow implements WorkflowDefinition {
    public static final String TYPE = "test.saga";
    public static final List<String> UNDONE = new CopyOnWriteArrayList<>();

    @Override public String type() { return TYPE; }
    @Override public int version() { return 1; }
    @Override public String initialStep() { return "reserve"; }

    @Override
    public Map<String, Step> steps() {
        return Map.of(
            "reserve", ctx -> {
                ctx.vars().put("reservationId", "res-" + ctx.businessKey());
                ctx.compensation("release_reservation");
                return Next.goTo("authorize");
            },
            "authorize", ctx -> {
                ctx.vars().put("authRef", "auth-" + ctx.businessKey());
                ctx.compensation("void_auth");
                ctx.vars().put("reservationId", "overwritten-later");
                return Next.goTo("source");
            },
            "source", ctx -> {
                // No durable handler: the closure runs in-process because the failure happens in this same step execution.
                ctx.compensation("notify_only", () -> UNDONE.add("notify_only:" + ctx.businessKey()));
                return Next.fail("no node can fulfil " + ctx.businessKey(), true);
            });
    }

    @Override
    public Map<String, Step> compensations() {
        return Map.of(
            "release_reservation", ctx -> { UNDONE.add("release_reservation:" + ctx.vars().get("reservationId")); return Next.complete(Map.of()); },
            "void_auth", ctx -> { UNDONE.add("void_auth:" + ctx.vars().get("authRef")); return Next.complete(Map.of()); });
    }
}
