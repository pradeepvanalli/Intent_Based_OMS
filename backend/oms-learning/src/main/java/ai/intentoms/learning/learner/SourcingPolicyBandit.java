package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * FS-01: a contextual bandit over the sourcing policies, the one place in this build that logs a genuinely sampled
 * {@code arms}/{@code chosenArm}/{@code propensity} rather than the generic decision fabric's deterministic 1.0 —
 * this is the component the whole propensity-logging design exists for (requirements doc §4).
 *
 * <p>Exploration is capped at 10% of traffic by default and only ever samples among arms the caller already filtered
 * to ones that clear the margin floor and the promise check — {@link #choose} never sees an infeasible arm.
 *
 * <p><b>Autonomy gating</b>: this always logs a decision record (so propensity data accumulates from day one,
 * satisfying OC-01/FS-01 regardless of autonomy level), but only returns {@code acted = true} — meaning the caller
 * should actually use the sampled policy — once the {@code sourcing_policy_bandit} learner's autonomy is
 * L2 (act with approval) or higher. Below that it's pure observation: the sourcing service keeps using its existing
 * intent/channel-default policy resolution unchanged.
 */
@Component
public class SourcingPolicyBandit {
    public static final String LEARNER_KEY = "sourcing_policy_bandit";
    public static final String DECISION_NAME = "sourcing_policy_bandit";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final double DEFAULT_EXPLORATION = 0.10;

    private final DecisionRecordRepository decisionRecords;
    private final LearnedFactRepository facts;
    private final LearnerRepository learners;

    public SourcingPolicyBandit(DecisionRecordRepository decisionRecords, LearnedFactRepository facts, LearnerRepository learners) {
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.learners = learners;
    }

    public record Choice(String policy, double propensity, boolean acted, String decisionRecordId) {}

    /**
     * @param eligiblePolicies arms already filtered to ones that clear the margin floor and promise check; never empty
     * @param defaultPolicy    the policy {@link DefaultSourcingService}'s existing logic would have picked, used
     *                         when there's no learned weight yet or the sampled arm isn't actually eligible
     */
    public Choice choose(String orderId, List<String> eligiblePolicies, String defaultPolicy, Map<String, Object> context, String actorRef) {
        LearnerState learner = learners.getOrCreate(LEARNER_KEY);
        Map<String, Double> weights = currentWeights(eligiblePolicies);
        // NF-04 (determinism): the explore/exploit draw is seeded here, once, and the seed itself is persisted on
        // the decision record below — replaying this record with the same seed and the same weights reproduces the
        // exact same sampled arm, which a draw straight off a shared SecureRandom could never guarantee.
        long seed = RANDOM.nextLong();
        String sampled = sample(eligiblePolicies, weights, learner.enabled(), new Random(seed));
        double propensity = learner.enabled() ? weights.getOrDefault(sampled, 1.0 / eligiblePolicies.size()) : 1.0;
        boolean acted = learner.enabled() && (learner.autonomyLevel() == AutonomyLevel.L2_ACT_WITH_APPROVAL || learner.autonomyLevel() == AutonomyLevel.L3_AUTONOMOUS);
        String chosen = acted ? sampled : defaultPolicy;
        if (!learner.enabled()) { sampled = defaultPolicy; propensity = 1.0; }

        String recordId = Ids.prefixed("dr");
        Map<String, Object> ctx = new LinkedHashMap<>(context);
        DecisionRecord record = new DecisionRecord(recordId, DECISION_NAME, 1, Mode.LIVE, "order", orderId, orderId, Layer.L1_SYSTEM_ONE, Band.ACT, "act",
            sampled, Map.of(), propensity, "bandit", "fs-01", Json.sha256(ctx), ctx, ctx, List.of(), List.of(), 0, 0, 0, false, null, actorRef,
            Map.of("acted", acted, "autonomyLevel", learner.autonomyLevel().name(), "appliedPolicy", chosen, "randomSeed", seed), null, null, null, null, Map.of(), Instant.now(),
            ctx, List.copyOf(eligiblePolicies), sampled, propensity, 1);
        decisionRecords.insert(record);
        learners.recordRun(LEARNER_KEY, Instant.now());
        return new Choice(chosen, propensity, acted, recordId);
    }

    /** Re-derives the arm a past call to {@link #choose} would have sampled, from the seed stored in its decision
     * record's {@code details.randomSeed} — the determinism check NF-04 requires. */
    public static String replay(List<String> eligiblePolicies, Map<String, Double> weights, long seed) {
        return sample(eligiblePolicies, weights, true, new Random(seed));
    }

    /** Equal weight over eligible arms, overridden per-arm by the learned SOURCING_POLICY_PROPENSITY fact when present. */
    private Map<String, Double> currentWeights(List<String> eligible) {
        Map<String, Double> uniform = new LinkedHashMap<>();
        for (String p : eligible) uniform.put(p, 1.0 / eligible.size());
        var fact = facts.active(SubjectType.SEGMENT, "default", FactKey.SOURCING_POLICY_PROPENSITY);
        if (fact.isEmpty() || !(fact.get().value() instanceof TypedValue.ListValue lv)) return uniform;
        // Stored as alternating "policy:weight" labels, e.g. ["ship_complete:0.4", "split_up_to_k:0.2", ...]
        Map<String, Double> learned = new LinkedHashMap<>();
        for (String label : lv.values()) {
            int i = label.indexOf(':');
            if (i < 0) continue;
            try { learned.put(label.substring(0, i), Double.parseDouble(label.substring(i + 1))); } catch (NumberFormatException ignored) {}
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        double total = 0;
        for (String p : eligible) { double w = learned.getOrDefault(p, 0.05); weights.put(p, w); total += w; }
        if (total <= 0) return uniform;
        for (var e : weights.entrySet()) e.setValue(e.getValue() / total);
        return weights;
    }

    /** Epsilon-greedy: with probability (1 - exploration) pick the highest-weight arm, else sample uniformly among
     * every eligible arm (the top arm included, so this is a floor of exploration/n per arm, never exactly zero —
     * the mitigation for the "feedback loop" failure mode in requirements doc §11: a node marked unreliable still
     * gets a nonzero chance to prove it recovered). The arm actually drawn is what gets logged as
     * chosenArm/propensity, never "the plan," so replay can reconstruct exactly what the system would have done
     * under a different weight vector. Takes the caller's seeded {@link Random} rather than a shared instance, so
     * the same seed always reproduces the same draw (NF-04). */
    private static String sample(List<String> eligible, Map<String, Double> weights, boolean enabled, Random rng) {
        if (!enabled || eligible.size() == 1) return eligible.get(0);
        if (rng.nextDouble() >= DEFAULT_EXPLORATION) {
            return eligible.stream().max((a, b) -> Double.compare(weights.getOrDefault(a, 0.0), weights.getOrDefault(b, 0.0))).orElse(eligible.get(0));
        }
        List<String> others = new ArrayList<>(eligible);
        return others.get(rng.nextInt(others.size()));
    }
}
