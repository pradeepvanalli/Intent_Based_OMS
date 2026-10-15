package ai.intentoms.workflow.model;

/** One step of a {@link WorkflowDefinition}. Returns what the engine should do next (FR-WF-01). */
@FunctionalInterface
public interface Step {
    Next run(WorkflowContext ctx) throws Exception;
}
