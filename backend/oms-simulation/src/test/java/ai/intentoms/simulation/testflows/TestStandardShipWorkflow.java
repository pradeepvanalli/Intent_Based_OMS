package ai.intentoms.simulation.testflows;

import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Minimal {@code order.standard_ship} definition for this module's tests: the real workflow lives in oms-orchestration;
 * capture only needs a registered type so {@code WorkflowEngine.start} succeeds (same pattern as oms-order's tests).
 */
@Component
public class TestStandardShipWorkflow implements WorkflowDefinition {
    public static final String TYPE = "order.standard_ship";

    @Override public String type() { return TYPE; }
    @Override public int version() { return 1; }
    @Override public String initialStep() { return "done"; }

    @Override
    public Map<String, Step> steps() {
        return Map.of("done", ctx -> { ctx.log("test workflow completed", Map.of("orderId", ctx.businessKey())); return Next.complete(Map.of("orderId", ctx.businessKey())); });
    }
}
