package ai.intentoms.sourcing.model;

import java.util.List;

/**
 * Outcome of {@code SourcingService.source/resource} (FR-SRC-03, FR-SRC-04, FR-SRC-07).
 *
 * @param status           SOURCED (fulfilment groups persisted), REQUIRES_APPROVAL (margin floor breached, nothing
 *                         persisted, alternatives offered), NO_FEASIBLE_NODE
 * @param plan             the chosen (or blocked) plan; null when no node was feasible
 * @param alternatives     other plans, best first — for REQUIRES_APPROVAL only those that meet the floor
 * @param decisionRecordId record id of the {@code sourcing_node_score} decision (null when the decision could not run)
 * @param reasons          human-readable reasons for the status / policy choice
 */
public record SourcingResult(Status status, SourcingPlan plan, List<SourcingPlan> alternatives, String decisionRecordId, List<String> reasons) {
    public enum Status { SOURCED, REQUIRES_APPROVAL, NO_FEASIBLE_NODE }

    public SourcingResult {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean requiresApproval() { return status == Status.REQUIRES_APPROVAL; }
    public boolean sourced() { return status == Status.SOURCED; }
}
