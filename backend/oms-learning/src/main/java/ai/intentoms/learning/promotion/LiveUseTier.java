package ai.intentoms.learning.promotion;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.learning.governance.LiveFamilies;
import ai.intentoms.learning.model.PolicyChangeset;

import java.util.List;

/**
 * D-3 (EX-07): turning a learned family on for live decisions is as risky as the riskiest decision it feeds. The tier
 * is the highest live tier among the family's {@link LiveFamilies.Domain#affectedDecisions() affected decisions}, and
 * never below T1: {@code fraud.signal_risk} and {@code fraud.address_reuse_count} feed payment_risk (T2) and
 * {@code cs.appeasement_level} feeds appeasement (T2), so their live use always needs a person's approval. Used when a
 * live-use proposal is drafted and again by the promotion gate, so a proposal stored with a lower tier is still gated
 * at the right one.
 */
public final class LiveUseTier {
    private LiveUseTier() {}

    public static PolicyChangeset.RiskTier of(String family, DecisionDefinitionRepository definitions) {
        PolicyChangeset.RiskTier tier = PolicyChangeset.RiskTier.T1;
        for (String decision : LiveFamilies.of(family).map(LiveFamilies.Domain::affectedDecisions).orElse(List.of())) {
            var live = definitions.byStatus(decision, "live").stream().findFirst();
            if (live.isEmpty() || live.get().definition().tier() == null) continue;
            PolicyChangeset.RiskTier t = PolicyChangeset.RiskTier.valueOf(live.get().definition().tier().name());
            if (t.compareTo(tier) > 0) tier = t;
        }
        return tier;
    }

    /** The family behind a {@code parameter:live:<family>} target, or null for any other target. */
    public static String familyOf(PolicyChangeset cs) {
        if (cs == null || cs.target() == null || !"parameter".equals(cs.target().type())) return null;
        String name = cs.target().name() == null ? "" : cs.target().name();
        return name.startsWith("live:") ? name.substring("live:".length()) : null;
    }
}
