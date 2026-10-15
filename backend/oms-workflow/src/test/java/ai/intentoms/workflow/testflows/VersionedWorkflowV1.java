package ai.intentoms.workflow.testflows;

import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** v1 of a two-version type used to prove instances stay pinned to their version (FR-WF-08). */
@Component
public class VersionedWorkflowV1 implements WorkflowDefinition {
    public static final String TYPE = "test.versioned";

    @Override public String type() { return TYPE; }
    @Override public int version() { return 1; }
    @Override public String initialStep() { return "a"; }

    @Override
    public Map<String, Step> steps() {
        return Map.of(
            "a", ctx -> Next.sleep(Duration.ofHours(1), "b"),
            "b", ctx -> { ctx.vars().put("ranVersion", 1); return Next.complete(Map.of("version", 1)); });
    }

    /** v2, registered at runtime by the test to simulate a deploy while v1 instances are in flight. */
    public static final class V2 implements WorkflowDefinition {
        @Override public String type() { return TYPE; }
        @Override public int version() { return 2; }
        @Override public String initialStep() { return "a"; }
        @Override public Map<String, Step> steps() {
            return Map.of(
                "a", ctx -> Next.sleep(Duration.ofHours(1), "b"),
                "b", ctx -> { ctx.vars().put("ranVersion", 2); return Next.complete(Map.of("version", 2)); });
        }
    }
}
