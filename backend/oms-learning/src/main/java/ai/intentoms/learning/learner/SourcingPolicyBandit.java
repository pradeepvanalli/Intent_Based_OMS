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
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * FS-01: a contextual bandit over the sourcing policies. The context is the order's channel, intent, basket size,
 * destination region, customer segment and the network's load ({@link #bucket}). Exploration is a tenant setting
 * ({@code learning.bandit.exploration}, default and maximum 10% of traffic) and only ever samples among arms the
 * caller already filtered to ones that clear the margin floor, keep the promise and fit the order type, so it never
 * sees an infeasible arm.
 *
 * <p><b>What is logged (OC-01)</b>: the record's {@code chosenArm} is always the policy actually applied, with the real
 * probability that policy had of being applied. When the bandit acts, that is the epsilon-greedy draw probability
 * ((1 - epsilon) for the top arm plus epsilon / n for every arm). When it does not act, the applied policy is the
 * caller's deterministic default with propensity 1.0, and the arm the bandit would have drawn is kept in
 * {@code details.shadow} so its counterfactual can still be estimated. Off-policy evaluation reads exactly these.
 *
 * <p>Sourcing calls {@link #draw} first, finishes choosing (a fallback when the drawn policy has no plan, the
 * split-acceptability check) and then {@link #log}s the policy it really used. When that differs from the drawn arm
 * the record says so ({@code details.overridden}) and carries propensity 1.0 for the deterministic override; the
 * reward learner leaves such records out, because the bandit's arm was not what the customer got.
 *
 * <p><b>When it acts</b>: only while the {@code sourcing.policy_bandit} parameter family is promoted for live use and
 * the learner is on, not paused and above L0 ({@link ParameterResolver#isLive}). Otherwise sourcing keeps its normal
 * intent/channel default. Every logged choice emits {@code decision.evaluated}, so an episode opens and outcomes attach.
 */
@Component
public class SourcingPolicyBandit implements GovernedLearner {
    public static final String LEARNER_KEY = "sourcing_policy_bandit";
    public static final String KEY = LEARNER_KEY;
    public static final String DECISION_NAME = "sourcing_policy_bandit";
    public static final String LIVE_PARAMETER = "sourcing.policy_bandit";
    public static final String EXPLORATION_KEY = "learning.bandit.exploration";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final double DEFAULT_EXPLORATION = 0.10;

    private final DecisionRecordRepository decisionRecords;
    private final LearnedFactRepository facts;
    private final LearnerGovernance governance;
    private final ParameterResolver parameters;
    private final EventStore events;
    private final TenantConfigStore config;

    public SourcingPolicyBandit(DecisionRecordRepository decisionRecords, LearnedFactRepository facts, LearnerGovernance governance,
                                ParameterResolver parameters, EventStore events, TenantConfigStore config) {
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.governance = governance;
        this.parameters = parameters;
        this.events = events;
        this.config = config;
    }

    public record Choice(String policy, double propensity, boolean acted, String decisionRecordId) {}

    /**
     * A draw that has not been logged yet. {@code applied} is what sourcing should use (the drawn arm when the bandit
     * acts, else the default); {@code sampled} is the arm the bandit drew, null when the learner may not run.
     */
    public record Draw(String bucket, Map<String, Double> weights, String weightsFrom, double exploration, long seed, String sampled, double sampledPropensity,
                       boolean acted, String applied, String defaultPolicy, List<String> eligible, Map<String, Object> context, LearnerGovernance.Gate gate,
                       boolean heldOut) {}

    /**
     * Draws and logs in one step (the applied policy is the drawn arm or the default). Sourcing uses {@link #draw}
     * and {@link #log} so the record names the policy finally used.
     *
     * @param eligiblePolicies arms already filtered to ones that clear the margin floor and promise check; never empty
     * @param defaultPolicy    the policy {@code DefaultSourcingService}'s existing logic would have picked
     */
    public Choice choose(String orderId, List<String> eligiblePolicies, String defaultPolicy, Map<String, Object> context, String actorRef) {
        String key = ai.intentoms.learning.governance.LearningSubject.current()
            .orElseGet(() -> ai.intentoms.learning.governance.LearningSubject.routingKey("order", orderId, context == null ? null : context.get("customerRef")));
        Draw d = ai.intentoms.learning.governance.LearningSubject.with(key, () -> draw(eligiblePolicies, defaultPolicy, context));
        return log(orderId, d, d.applied(), null, actorRef);
    }

    /** Draws an arm for this context without logging anything. */
    public Draw draw(List<String> eligiblePolicies, String defaultPolicy, Map<String, Object> context) {
        LearnerGovernance.Gate gate = governance.begin(LEARNER_KEY);
        String bucket = bucket(context);
        WeightSource source = currentWeights(eligiblePolicies, bucket);
        Map<String, Double> weights = source.weights();
        double exploration = exploration();
        // NF-04 (determinism): the draw is seeded once and the seed is stored on the record, so replaying it with the
        // same weights and exploration share reproduces the same sampled arm.
        long seed = RANDOM.nextLong();
        String sampled = gate.mayRun() ? sample(eligiblePolicies, weights, exploration, new Random(seed)) : null;
        double sampledP = sampled == null ? 0.0 : drawProbability(sampled, eligiblePolicies, weights, exploration);
        // EX-04: a holdout customer always gets the default policy, so lift measures what the bandit adds. The customer
        // is the learning subject the caller set (sourcing sets it per order; choose() sets it from the order id).
        String routingKey = ai.intentoms.learning.governance.LearningSubject.current().orElse(null);
        boolean heldOut = routingKey != null && parameters.inHoldout(routingKey);
        boolean acted = sampled != null && !heldOut && parameters.isLiveFor(routingKey, LIVE_PARAMETER);
        String applied = acted ? sampled : defaultPolicy;
        return new Draw(bucket, weights, source.from(), exploration, seed, sampled, sampledP, acted, applied, defaultPolicy, List.copyOf(eligiblePolicies),
            new LinkedHashMap<>(context), gate, heldOut);
    }

    /**
     * Logs the draw with the policy sourcing finally used. {@code overrideReason} says why it differs from
     * {@code draw.applied()} (null when it does not).
     */
    public Choice log(String orderId, Draw d, String finalPolicy, String overrideReason, String actorRef) {
        String used = finalPolicy == null ? d.applied() : finalPolicy;
        boolean overridden = used != null && !used.equals(d.applied());
        double propensity = d.acted() && !overridden ? d.sampledPropensity() : 1.0;

        String recordId = Ids.prefixed("dr");
        Map<String, Object> ctx = new LinkedHashMap<>(d.context());
        ctx.put("bucket", d.bucket());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("acted", d.acted());
        details.put("bucket", d.bucket());
        details.put("weightsFrom", d.weightsFrom());
        details.put("autonomyLevel", d.gate().level().name());
        details.put("gate", d.gate().reason());
        details.put("appliedPolicy", used);
        details.put("exploration", d.exploration());
        details.put("weights", d.weights());
        details.put("randomSeed", d.seed());
        details.put("mode", Mode.LIVE.name());
        if (d.heldOut()) details.put("experiment", Map.of("arm", "holdout"));
        if (overridden) {
            details.put("overridden", true);
            details.put("banditPolicy", d.applied());
            details.put("overrideReason", overrideReason == null ? "sourcing chose another policy" : overrideReason);
        }
        if (d.sampled() != null && !d.acted()) details.put("shadow", Map.of("arm", d.sampled(), "propensity", d.sampledPropensity()));
        List<String> arms = new ArrayList<>(d.eligible());
        if (used != null && !arms.contains(used)) arms.add(used);
        Instant now = Instant.now();
        DecisionRecord record = new DecisionRecord(recordId, DECISION_NAME, 1, Mode.LIVE, "order", orderId, orderId, Layer.L1_SYSTEM_ONE, Band.ACT, "act",
            used, Map.of(), propensity, "bandit", "fs-01", Json.sha256(ctx), ctx, ctx, List.of(), List.of(), 0, 0, 0, false, null, actorRef,
            details, null, null, null, null, Map.of(), now, ctx, List.copyOf(arms), used, propensity, 1);
        decisionRecords.insert(record);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", DECISION_NAME);
        payload.put("version", 1);
        payload.put("mode", Mode.LIVE.name());
        payload.put("value", used == null ? "" : used);
        payload.put("propensity", propensity);
        payload.put("acted", d.acted());
        events.append("decision", recordId, 0, List.of(new NewEvent("decision.evaluated", payload, Actor.parse(actorRef), null, orderId,
            Map.of("subjectType", "order", "subjectId", orderId))));
        if (d.gate().mayRun()) governance.recordRunThrottled(LEARNER_KEY);
        return new Choice(used, propensity, d.acted(), recordId);
    }

    /** The tenant's exploration share, never above 10% (FS-01's cap). */
    double exploration() {
        try { return Math.max(0.0, Math.min(DEFAULT_EXPLORATION, config.getDouble(EXPLORATION_KEY, DEFAULT_EXPLORATION))); }
        catch (RuntimeException e) { return DEFAULT_EXPLORATION; }
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
        return replay(eligiblePolicies, weights, DEFAULT_EXPLORATION, seed);
    }

    /** As {@link #replay(List, Map, long)}, with the exploration share stored on the record. */
    public static String replay(List<String> eligiblePolicies, Map<String, Double> weights, double exploration, long seed) {
        return sample(eligiblePolicies, weights, exploration, new Random(seed));
    }

    /**
     * FS-01 context bucket: channel x intent x basket size x region x customer segment x network load. Basket size is
     * 1, 2-3 or 4+ units; region is the destination ZIP's first digit (US postal regions), else the state, else
     * "any"; segment is the customer's discovered segment ({@code SEGMENT_MEMBERSHIP}), else "none"; load is the
     * network state at sourcing time ("calm", "busy" or "strained", from {@link #loadBand}), else "any".
     */
    public static String bucket(Map<String, Object> context) {
        String channel = val(context.get("channel"), "any");
        String intent = val(context.get("intentPolicy"), "none");
        int units = context.get("basketUnits") instanceof Number n ? n.intValue() : context.get("lineCount") instanceof Number l ? l.intValue() : 1;
        String basket = units <= 1 ? "1" : units <= 3 ? "2-3" : "4+";
        String zip = val(context.get("destinationZip"), "");
        String digits = zip.replaceAll("[^0-9]", "");
        String region = !digits.isEmpty() ? "z" + digits.charAt(0) : val(context.get("destinationState"), "any");
        String segment = val(context.get("segment"), "none");
        String load = val(context.get("networkLoad"), "any");
        return channel + "|" + intent + "|" + basket + "|" + region + "|" + segment + "|" + load;
    }

    /** The bucket without segment and load: where a new segment or load band borrows weights from first. */
    public static String coarseBucket(String bucket) {
        String[] parts = bucket.split("\\|", -1);
        return parts.length <= 4 ? bucket : String.join("|", Arrays.copyOf(parts, 4));
    }

    /**
     * Network state for the bucket: the share of the candidates' daily capacity already used, as "calm" (below 50%),
     * "busy" (50-80%) or "strained" (above 80%). Unknown capacity gives "any".
     */
    public static String loadBand(double utilisation) {
        if (Double.isNaN(utilisation) || utilisation < 0) return "any";
        return utilisation < 0.5 ? "calm" : utilisation <= 0.8 ? "busy" : "strained";
    }

    public static String bucketSubject(String bucket) { return "bandit:" + bucket; }

    private static String val(Object o, String dflt) { return o == null || String.valueOf(o).isBlank() ? dflt : String.valueOf(o); }

    record WeightSource(Map<String, Double> weights, String from) {}

    /** The bucket's learned weights, else its coarse bucket's, else the pooled ones, else uniform over the eligible arms. */
    private WeightSource currentWeights(List<String> eligible, String bucket) {
        Map<String, Double> uniform = new LinkedHashMap<>();
        for (String p : eligible) uniform.put(p, 1.0 / eligible.size());
        LinkedHashSet<String> subjects = new LinkedHashSet<>(List.of(bucketSubject(bucket), bucketSubject(coarseBucket(bucket)), "default"));
        for (String subject : subjects) {
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
     * gets a nonzero chance to prove it recovered). Takes the caller's seeded {@link Random} rather than a shared
     * instance, so the same seed always reproduces the same draw (NF-04). */
    private static String sample(List<String> eligible, Map<String, Double> weights, double exploration, Random rng) {
        if (eligible.size() == 1) return eligible.get(0);
        if (rng.nextDouble() >= exploration) {
            return eligible.stream().max((a, b) -> Double.compare(weights.getOrDefault(a, 0.0), weights.getOrDefault(b, 0.0))).orElse(eligible.get(0));
        }
        List<String> others = new ArrayList<>(eligible);
        return others.get(rng.nextInt(others.size()));
    }
}
