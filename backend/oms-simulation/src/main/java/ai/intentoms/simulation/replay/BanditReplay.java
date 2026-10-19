package ai.intentoms.simulation.replay;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.learning.learner.SourcingPolicyBandit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * NF-04 for the sourcing-policy bandit, which writes its own records outside the decision fabric: its draw is
 * re-derived from the record's stored seed, weights and eligible arms, and the applied policy follows the recorded
 * acted flag (the sampled arm when it acted, the recorded default otherwise).
 */
final class BanditReplay {
    private BanditReplay() {}

    /** {@code applied}: the policy the replay applies; {@code sampled}: the replayed draw (null when the bandit did not run). */
    record Result(String applied, String sampled, boolean reproduced) {}

    static boolean isBandit(DecisionRecord r) { return SourcingPolicyBandit.DECISION_NAME.equals(r.decision()); }

    static Result replay(DecisionRecord r) {
        Map<String, Object> d = r.details() == null ? Map.of() : r.details();
        Map<String, Double> weights = weights(d.get("weights"));
        List<String> eligible = r.arms() == null ? List.of() : r.arms().stream().filter(weights::containsKey).toList();
        boolean acted = Boolean.TRUE.equals(d.get("acted"));
        boolean ran = acted || d.containsKey("shadow") || "ok".equals(String.valueOf(d.get("gate")));
        String sampled = ran && !eligible.isEmpty() && d.get("randomSeed") instanceof Number seed
            ? SourcingPolicyBandit.replay(eligible, weights, seed.longValue()) : null;
        String applied = acted ? sampled : d.get("appliedPolicy") != null ? String.valueOf(d.get("appliedPolicy")) : r.chosenArm();
        Object shadowArm = d.get("shadow") instanceof Map<?, ?> sh ? sh.get("arm") : null;
        boolean reproduced = Objects.equals(applied, r.chosenArm()) && (shadowArm == null || Objects.equals(String.valueOf(shadowArm), sampled));
        return new Result(applied, sampled, reproduced);
    }

    private static Map<String, Double> weights(Object raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v instanceof Number n) out.put(String.valueOf(k), n.doubleValue()); });
        return out;
    }
}
