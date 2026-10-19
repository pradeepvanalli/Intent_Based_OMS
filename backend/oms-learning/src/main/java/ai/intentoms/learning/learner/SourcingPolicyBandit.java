package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * FS-01: a contextual bandit over the sourcing policies. Exploration is capped at 10% of traffic by default and only
 * ever samples among arms the caller already filtered to ones that clear the margin floor and the promise check, so
 * {@link #choose} never sees an infeasible arm.
 *
 * <p><b>What is logged (OC-01)</b>: the record's {@code chosenArm} is always the policy actually applied, with the real
 * probability that policy had of being applied. When the bandit acts, that is the epsilon-greedy draw probability
 * ((1 - epsilon) for the top arm plus epsilon / n for every arm). When it does not act, the applied policy is the
 * caller's deterministic default with propensity 1.0, and the arm the bandit would have drawn is kept in
 * {@code details.shadow} so its counterfactual can still be estimated. Off-policy evaluation reads exactly these.
 *
 * <p><b>When it acts</b>: only while the {@code sourcing.policy_bandit} parameter family is promoted for live use and
 * the learner is on, not paused and above L0 ({@link ParameterResolver#isLive}). Otherwise sourcing keeps its normal
 * intent/channel default. Every call emits {@code decision.evaluated}, so an episode opens and outcomes attach to it.
 */
@Component
public class SourcingPolicyBandit implements GovernedLearner {
    public static final String LEARNER_KEY = "sourcing_policy_bandit";
    public static final String KEY = LEARNER_KEY;
    public static final String DECISION_NAME = "sourcing_policy_bandit";
    public static final String LIVE_PARAMETER = "sourcing.policy_bandit";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final double DEFAULT_EXPLORATION = 0.10;

    private final DecisionRecordRepository decisionRecords;
    private final LearnedFactRepository facts;
    private final LearnerGovernance governance;
    private final ParameterResolver parameters;
    private final EventStore events;

    public SourcingPolicyBandit(DecisionRecordRepository decisionRecords, LearnedFactRepository facts, LearnerGovernance governance,
                                ParameterResolver parameters, EventStore events) {
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.governance = governance;
        this.parameters = parameters;
        this.events = events;
    }

    public record Choice(String policy, double propensity, boolean acted, String decisionRecordId) {}

    /**
     * @param eligiblePolicies arms already filtered to ones that clear the margin floor and promise check; never empty
     * @param defaultPolicy    the policy {@code DefaultSourcingService}'s existing logic would have picked
     */
    public Choice choose(String orderId, List<String> eligiblePolicies, String defaultPolicy, Map<String, Object> context, String actorRef) {
        LearnerGovernance.Gate gate = governance.begin(LEARNER_KEY);
        String bucket = bucket(context);
        WeightSource source = currentWeights(eligiblePolicies, bucket);
        Map<String, Double> weights = source.weights();
        // NF-04 (determinism): the draw is seeded once and the seed is stored on the record, so replaying it with the
        // same weights reproduces the same sampled arm.
        long seed = RANDOM.nextLong();
        String sampled = gate.mayRun() ? sample(eligiblePolicies, weights, true, new Random(seed)) : null;
        double sampledP = sampled == null ? 0.0 : drawProbability(sampled, eligiblePolicies, weights, DEFAULT_EXPLORATION);
        // EX-04: a holdout customer always gets the default policy, so lift measures what the bandit adds.
        String routingKey = ai.intentoms.learning.governance.LearningSubject.routingKey("order", orderId, context == null ? null : context.get("customerRef"));
        boolean heldOut = parameters.inHoldout(routingKey);
        boolean acted = sampled != null && !heldOut && parameters.isLiveFor(routingKey, LIVE_PARAMETER);
        String applied = acted ? sampled : defaultPolicy;
        double propensity = acted ? sampledP : 1.0;

        String recordId = Ids.prefixed("dr");
        Map<String, Object> ctx = new LinkedHashMap<>(context);
        ctx.put("bucket", bucket);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("acted", acted);
        details.put("bucket", bucket);
        details.put("weightsFrom", source.from());
        details.put("autonomyLevel", gate.level().name());
        details.put("gate", gate.reason());
        details.put("appliedPolicy", applied);
        details.put("exploration", DEFAULT_EXPLORATION);
        details.put("weights", weights);
        details.put("randomSeed", seed);
        details.put("mode", Mode.LIVE.name());
        if (heldOut) details.put("experiment", Map.of("arm", "holdout"));
        if (sampled != null && !acted) details.put("shadow", Map.of("arm", sampled, "propensity", sampledP));
        List<String> arms = new ArrayList<>(eligiblePolicies);
        if (applied != null && !arms.contains(applied)) arms.add(applied);
        Instant now = Instant.now();
        DecisionRecord record = new DecisionRecord(recordId, DECISION_NAME, 1, Mode.LIVE, "order", orderId, orderId, Layer.L1_SYSTEM_ONE, Band.ACT, "act",
            applied, Map.of(), propensity, "bandit", "fs-01", Json.sha256(ctx), ctx, ctx, List.of(), List.of(), 0, 0, 0, false, null, actorRef,
            details, null, null, null, null, Map.of(), now, ctx, List.copyOf(arms), applied, propensity, 1);
        decisionRecords.insert(record);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", DECISION_NAME);
        payload.put("version", 1);
        payload.put("mode", Mode.LIVE.name());
        payload.put("value", applied == null ? "" : applied);
        payload.put("propensity", propensity);
        payload.put("acted", acted);
        events.append("decision", recordId, 0, List.of(new NewEvent("decision.evaluated", payload, Actor.parse(actorRef), null, orderId,
            Map.of("subjectType", "order", "subjectId", orderId))));
        if (gate.mayRun()) governance.recordRunThrottled(LEARNER_KEY);
        return new Choice(applied, propensity, acted, recordId);
    }

    /** Probability that epsilon-greedy picks {@code arm}: the greedy share for the top arm plus the uniform share. */
    static double drawProbability(String arm, List<String> eligible, Map<String, Double> weights, double exploration) {
        if (eligible.size() == 1) return 1.0;
        String top = eligible.stream().max((a, b) -> Double.compare(weights.getOrDefault(a, 0.0), weights.getOrDefault(b, 0.0))).orElse(eligible.get(0));
        return (arm.equals(top) ? 1.0 - exploration : 0.0) + exploration / eligible.size();
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Sourcing policy explorer", "Tries alternative sourcing policies on a small share of orders and learns which one gives the best outcome.",
            LearnerDescriptor.Loop.FAST, "FS-01").readsOutcomesOf(DECISION_NAME).feeds(LIVE_PARAMETER);
    }

    /** Re-derives the arm a past call to {@link #choose} would have sampled, from the seed stored in its decision
     * record's {@code details.randomSeed} — the determinism check NF-04 requires. */
    public static String replay(List<String> eligiblePolicies, Map<String, Double> weights, long seed) {
        return sample(eligiblePolicies, weights, true, new Random(seed));
    }

    /**
     * FS-01 context bucket: channel x intent x basket size x region. Basket size is 1, 2-3 or 4+ units; region is the
     * destination ZIP's first digit (US postal regions), else the state, else "any".
     */
    public static String bucket(Map<String, Object> context) {
        String channel = val(context.get("channel"), "any");
        String intent = val(context.get("intentPolicy"), "none");
        int units = context.get("basketUnits") instanceof Number n ? n.intValue() : context.get("lineCount") instanceof Number l ? l.intValue() : 1;
        String basket = units <= 1 ? "1" : units <= 3 ? "2-3" : "4+";
        String zip = val(context.get("destinationZip"), "");
        String digits = zip.replaceAll("[^0-9]", "");
        String region = !digits.isEmpty() ? "z" + digits.charAt(0) : val(context.get("destinationState"), "any");
        return channel + "|" + intent + "|" + basket + "|" + region;
    }

    public static String bucketSubject(String bucket) { return "bandit:" + bucket; }

    private static String val(Object o, String dflt) { return o == null || String.valueOf(o).isBlank() ? dflt : String.valueOf(o); }

    record WeightSource(Map<String, Double> weights, String from) {}

    /** The bucket's learned weights, else the pooled learned weights, else uniform over the eligible arms. */
    private WeightSource currentWeights(List<String> eligible, String bucket) {
        Map<String, Double> uniform = new LinkedHashMap<>();
        for (String p : eligible) uniform.put(p, 1.0 / eligible.size());
        for (String subject : List.of(bucketSubject(bucket), "default")) {
            var fact = facts.active(SubjectType.SEGMENT, subject, FactKey.SOURCING_POLICY_PROPENSITY);
            if (fact.isEmpty() || !(fact.get().value() instanceof TypedValue.ListValue lv)) continue;
            Map<String, Double> weights = normalise(parseWeights(lv.values()), eligible);
            if (weights != null) return new WeightSource(weights, subject + "@v" + fact.get().version());
        }
        return new WeightSource(uniform, "uniform");
    }

    /** Stored as "policy:weight" labels, e.g. ["ship_complete:0.4", "split_up_to_k:0.2", ...]. */
    static Map<String, Double> parseWeights(List<String> labels) {
        Map<String, Double> learned = new LinkedHashMap<>();
        for (String label : labels) {
            int i = label.lastIndexOf(':');
            if (i < 0) continue;
            try { learned.put(label.substring(0, i), Double.parseDouble(label.substring(i + 1))); } catch (NumberFormatException ignored) {}
        }
        return learned;
    }

    private static Map<String, Double> normalise(Map<String, Double> learned, List<String> eligible) {
        Map<String, Double> weights = new LinkedHashMap<>();
        double total = 0;
        for (String p : eligible) { double w = learned.getOrDefault(p, 0.05); weights.put(p, w); total += w; }
        if (total <= 0) return null;
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
