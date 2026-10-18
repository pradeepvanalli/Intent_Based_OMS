package ai.intentoms.simulation.learning;

import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;

/**
 * What a proposed {@link PolicyChangeset} would have chosen for an already-logged decision, without re-running the
 * full L0→L1→L2→L3 pipeline — the logged confidence and band are enough to determine whether a {@code
 * ThresholdChange} would have moved this specific record into or out of the act band, which is exactly the signal
 * the IPS/doubly-robust estimator needs (did the proposed policy's arm match the logged one).
 *
 * <p><b>Scope:</b> {@code ThresholdChange} is fully simulated. {@code WeightChange}, {@code RuleAdd}, {@code
 * RuleRetire}, {@code ParameterChange} and {@code AllowListChange} would need to re-run decision-specific scoring
 * or rule logic this estimator doesn't have — for those, the proposed arm defaults to the logged arm (no simulated
 * behaviour change for that record). A changeset mixing a threshold change with other change types still gets a
 * real threshold simulation; only the other change types' effect is left at "no observed difference" until a
 * decision-specific simulator is built for them.
 */
public final class ProposedArmSimulator {
    private ProposedArmSimulator() {}

    /** The arm the proposed policy would choose for this record, or null when it would no longer act (escalate/confirm
     * instead of the logged arm) — null correctly scores as "doesn't match the logged arm" in the IPS indicator. */
    public static String proposedArm(LoggedRecord r, PolicyChangeset changeset) {
        String arm = r.chosenArm();
        boolean stillActs = !"ESCALATE".equals(r.band());
        for (PolicyChange change : changeset.changes()) {
            if (change instanceof PolicyChange.ThresholdChange tc && "ACT".equals(tc.band())) {
                stillActs = r.confidence() >= tc.to();
            }
        }
        return stillActs ? arm : null;
    }
}
