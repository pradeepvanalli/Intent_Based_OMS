package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FD-01/FD-02/FD-03: tunes each fraud decision's act-band threshold to the tenant's false-positive target while
 * minimising expected cost: fraud that gets through costs its order value, a good order blocked costs
 * {@code learning.fraud.block_cost} (default 25, the margin and goodwill lost). On the labelled records of the last 30
 * days it replays every threshold within one step of the current one and picks the cheapest that keeps the share of
 * good orders blocked at or under {@code learning.fraud.fp_target} (default 5%); if none does, the one blocking the
 * fewest good orders. The result is a T2 {@link Proposal} with a {@link PolicyChange.ThresholdChange}, step-capped at
 * {@code oms.learning.threshold-max-step} (default 0.05, GV-02); it never touches the live decision. Each run also
 * records the measured false-positive rate as a {@code FALSE_POSITIVE_RATE} fact on the decision.
 */
@Component
public class FraudThresholdTuner implements GovernedLearner {
    public static final String KEY = "fraud_threshold_tuner";
    private static final Logger log = LoggerFactory.getLogger(FraudThresholdTuner.class);
    private static final List<String> FRAUD_DECISIONS = List.of("payment_risk", "rto_risk", "address_risk");
    static final int MIN_LABELLED = 20;

    private final DecisionRecordRepository decisionRecords;
    private final DecisionDefinitionRepository definitions;
    private final ProposalRepository proposals;
    private final LearnedFactRepository facts;
    private final TenantConfigStore config;
    private final LearnerGovernance governance;
    private final HypothesisRepository hypotheses;

    @Value("${oms.learning.fraud-fp-target:0.05}") private double defaultFpTarget;
    @Value("${oms.learning.threshold-max-step:0.05}") private double maxStep;

    public FraudThresholdTuner(DecisionRecordRepository decisionRecords, DecisionDefinitionRepository definitions, ProposalRepository proposals,
                               LearnedFactRepository facts, TenantConfigStore config, LearnerGovernance governance, HypothesisRepository hypotheses) {
        this.governance = governance;
        this.hypotheses = hypotheses;
        this.decisionRecords = decisionRecords;
        this.definitions = definitions;
        this.proposals = proposals;
        this.facts = facts;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${oms.learning.threshold-tune-ms:86400000}", initialDelayString = "${oms.learning.threshold-tune-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Fraud threshold tuning failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(30))));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::tuneTenant);
    }

    private void tuneTenant() {
        if (!governance.begin(KEY).mayPropose()) return;
        governance.recordRun(KEY);
        for (String decision : FRAUD_DECISIONS) {
            try { tuneOne(decision); } catch (RuntimeException e) { log.warn("Threshold tuning skipped for {}: {}", decision, e.toString()); }
        }
    }

    private void tuneOne(String decision) {
        Instant since = Instant.now().minus(Duration.ofDays(30));
        List<Integer> versions = decisionRecords.activeVersions(decision, since).stream().map(a -> a[0]).toList();
        if (versions.isEmpty()) return;
        int version = versions.get(versions.size() - 1);
        var def = definitions.find(decision, version);
        if (def.isEmpty()) return;
        double current = def.get().definition().bands() == null ? 0.9 : def.get().definition().bands().actGte();

        List<Case> cases = new ArrayList<>();
        for (int v : versions) for (DecisionRecord r : decisionRecords.labelledBetween(decision, v, since, Instant.now())) {
            Boolean fraud = label(r);
            if (fraud == null) continue;
            cases.add(new Case(r.confidence(), Boolean.TRUE.equals(r.value()) || "true".equals(String.valueOf(r.value())), fraud, amount(r)));
        }
        if (cases.size() < MIN_LABELLED) return; // not enough ground truth yet

        double fpTarget = config.getDouble("learning.fraud.fp_target", defaultFpTarget);
        double blockCost = config.getDouble("learning.fraud.block_cost", 25.0);
        Choice c = optimise(cases, current, fpTarget, blockCost, maxStep);
        recordFalsePositiveRate(decision, c.currentFpRate(), cases.size());
        if (Math.abs(c.threshold() - current) < 0.01) return;
        if (proposals.existsOpenFor("decision:" + decision)) return; // one in-flight proposal per decision

        // The numbers behind the proposal travel as its hypothesis, so a reviewer sees why without re-running anything.
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decision), "fraud_and_abuse_loss", c.cost() <= c.currentCost() ? Direction.DECREASE : Direction.INCREASE,
            Map.of("lever", "threshold", "band", "act", "from", current, "to", c.threshold()), 0.6,
            List.of(String.format(Locale.ROOT, "labelled_cases=%d fp_target=%.3f block_cost=%.2f", cases.size(), fpTarget, blockCost),
                String.format(Locale.ROOT, "current threshold=%.2f fp_rate=%.3f expected_cost=%.2f", current, c.currentFpRate(), c.currentCost()),
                String.format(Locale.ROOT, "proposed threshold=%.2f fp_rate=%.3f fn_rate=%.3f expected_cost=%.2f", c.threshold(), c.fpRate(), c.fnRate(), c.cost())),
            c.reason().length() > 280 ? c.reason().substring(0, 280) : c.reason(), Hypothesis.Status.DRAFTED, Instant.now());
        hypotheses.insert(h);
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", decision), version,
            List.of(new PolicyChange.ThresholdChange(decision, "act", current, c.threshold())), h.hypothesisId(), PolicyChangeset.RiskTier.T2);
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, h.hypothesisId(), PolicyChangeset.RiskTier.T2, ProposalStatus.DRAFT, null, null,
            "system:" + KEY, Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        log.info("Proposed act-band threshold change for {}: {} -> {} ({})", decision, current, c.threshold(), c.reason());
    }

    /** One labelled decision: the model's confidence, whether it said "risky", what really happened, the order value. */
    record Case(double confidence, boolean saidRisky, boolean fraud, double amount) {}

    record Choice(double threshold, double fpRate, double fnRate, double cost, double currentFpRate, double currentCost, String reason) {}

    /**
     * Replays thresholds within {@code maxStep} of {@code current} (0.01 apart, kept inside [0.5, 0.99]) and returns the
     * cheapest one whose false-positive rate meets the target, else the one with the lowest false-positive rate. A case
     * counts as blocked at threshold t when the model said "risky" with confidence at least t. Pure, for tests.
     */
    static Choice optimise(List<Case> cases, double current, double fpTarget, double blockCost, double maxStep) {
        double[] cur = evaluate(cases, current, blockCost);
        double lo = Math.max(0.5, current - maxStep), hi = Math.min(0.99, current + maxStep);
        double best = current;
        double[] bestEval = cur;
        boolean bestMeets = cur[0] <= fpTarget;
        for (int i = 0; ; i++) {
            double t = Math.round((lo + i * 0.01) * 100) / 100.0;
            if (t > hi + 1e-9) break;
            double[] ev = evaluate(cases, t, blockCost);
            boolean meets = ev[0] <= fpTarget;
            boolean better;
            if (meets != bestMeets) better = meets;
            else if (meets) better = ev[2] < bestEval[2] - 1e-9 || (Math.abs(ev[2] - bestEval[2]) <= 1e-9 && Math.abs(t - current) < Math.abs(best - current));
            else better = ev[0] < bestEval[0] - 1e-9 || (Math.abs(ev[0] - bestEval[0]) <= 1e-9 && ev[2] < bestEval[2] - 1e-9);
            if (better) { best = t; bestEval = ev; bestMeets = meets; }
        }
        String reason = String.format(Locale.ROOT, "at %.2f: %.1f%% of good orders blocked (target %.1f%%), %.1f%% of fraud missed, expected cost %.2f vs %.2f at %.2f",
            best, bestEval[0] * 100, fpTarget * 100, bestEval[1] * 100, bestEval[2], cur[2], current);
        return new Choice(best, bestEval[0], bestEval[1], bestEval[2], cur[0], cur[2], reason);
    }

    /** [false-positive rate, false-negative rate, expected cost] at threshold t. */
    static double[] evaluate(List<Case> cases, double t, double blockCost) {
        long good = 0, goodBlocked = 0, fraud = 0, fraudMissed = 0;
        double cost = 0;
        for (Case c : cases) {
            boolean blocked = c.saidRisky() && c.confidence() >= t - 1e-9;
            if (c.fraud()) { fraud++; if (!blocked) { fraudMissed++; cost += c.amount(); } }
            else { good++; if (blocked) { goodBlocked++; cost += blockCost; } }
        }
        return new double[]{good == 0 ? 0 : (double) goodBlocked / good, fraud == 0 ? 0 : (double) fraudMissed / fraud, cost};
    }

    private void recordFalsePositiveRate(String decision, double fpRate, int n) {
        Instant now = Instant.now();
        var prev = facts.active(SubjectType.DECISION, decision, FactKey.FALSE_POSITIVE_RATE);
        facts.supersede(SubjectType.DECISION, decision, FactKey.FALSE_POSITIVE_RATE, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.DECISION, decision, FactKey.FALSE_POSITIVE_RATE, new TypedValue.NumberValue(fpRate),
            Math.min(1.0, n / 100.0), n, KEY, prev.map(f -> f.version() + 1).orElse(1), now, null, now));
    }

    private static double amount(DecisionRecord r) {
        Object total = r.input() == null ? null : Json.path(r.input(), "order.total");
        if (total == null && r.input() != null) total = r.input().get("order.total");
        return total instanceof Number n ? n.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    static Boolean label(DecisionRecord r) {
        Object outcome = r.labels() == null ? null : r.labels().get("fraud_outcome");
        if (!(outcome instanceof Map<?, ?> m)) return null;
        Object confirmed = ((Map<String, Object>) m).get("confirmed");
        return confirmed instanceof Boolean b ? b : null;
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Fraud threshold tuner", "Moves fraud thresholds toward your false-positive target, weighing fraud loss against blocking good orders.", LearnerDescriptor.Loop.FAST, "FD-01, FD-02, FD-03").readsOutcomesOf("payment_risk", "rto_risk", "address_risk");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
