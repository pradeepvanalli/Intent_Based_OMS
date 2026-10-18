package ai.intentoms.simulation.learning;

import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;

import java.util.Locale;

/**
 * Fast path for threshold-only changesets: what the proposed bands would have done with an already-logged decision,
 * from its logged confidence and band alone, without re-running the pipeline. Raising the act threshold above a record's
 * confidence means the proposed policy would no longer act on it (null arm); lowering it below means a record that
 * confirmed or escalated would now act on its logged value. Every other change type is re-simulated through the
 * candidate definition by {@link ProposalEvaluationService}.
 */
public final class ProposedArmSimulator {
    private ProposedArmSimulator() {}

    /** The arm the proposed policy would choose for this record, or null when it would no longer act. */
    public static String proposedArm(LoggedRecord r, PolicyChangeset changeset) {
        boolean acts = "ACT".equals(r.band());
        for (PolicyChange change : changeset.changes()) {
            if (change instanceof PolicyChange.ThresholdChange tc && "act".equals(tc.band().toLowerCase(Locale.ROOT))) acts = r.confidence() >= tc.to();
        }
        return acts ? r.chosenArm() : null;
    }
}
