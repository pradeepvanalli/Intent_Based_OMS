package ai.intentoms.copilot.testflows;

import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Minimal {@code order.standard_ship} so order capture can start a workflow in this module's tests (the real one lives in oms-orchestration). */
@Component
public class TestStandardShipWorkflow implements WorkflowDefinition {
    @Override public String type() { return "order.standard_ship"; }
    @Override public int version() { return 1; }
    @Override public String initialStep() { return "done"; }
    @Override public Map<String, Step> steps() { return Map.of("done", ctx -> Next.complete(Map.of("orderId", ctx.businessKey()))); }
}
