package ai.intentoms.sourcing.learning;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.sourcing.config.SourcingConfigKeys;
import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.plan.SoftScorer;
import ai.intentoms.sourcing.plan.SourcingPlanRepository;
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
 * FS-04: proposes moving the sourcing plan-score weights ({@code sourcing.weights}: cost, time, soft, split) toward
 * the components that go with better outcomes. For each order whose chosen plan has a rewarded
 * {@code sourcing_node_score} episode, the plan's score components are paired with the episode's reward; a component
 * whose higher values go with higher reward (positive correlation) gains weight, a negatively correlated one loses
 * it, each by at most {@code maxStep} per proposal, and the vector is renormalised to sum to 1. The result is a
 * {@code WeightChange} proposal (T1) on {@code config:sourcing.weights}, with the evidence in its hypothesis; it is
 * simulated, approved and applied through the normal promotion gate, never written directly.
 */
@Component
public class SourcingWeightLearner implements GovernedLearner {
    public static final String KEY = "sourcing_weights";
    public static final String TARGET = "config:" + SourcingConfigKeys.WEIGHTS;
    static final List<String> COMPONENTS = List.of("cost", "time", "soft", "split");
    private static final Logger log = LoggerFactory.getLogger(SourcingWeightLearner.class);

    private final SourcingPlanRepository plans;
    private final EpisodeRepository episodes;
    private final ProposalRepository proposals;
    private final HypothesisRepository hypotheses;
    private final SourcingSettings settings;
    private final DecisionRecordRepository decisionRecords;
    private final LearnerGovernance governance;

    @Value("${oms.learning.fs04.window-days:60}") private int windowDays = 60;
    @Value("${oms.learning.fs04.min-samples:50}") private int minSamples = 50;
    @Value("${oms.learning.fs04.max-step:0.05}") private double maxStep = 0.05;

    public SourcingWeightLearner(SourcingPlanRepository plans, EpisodeRepository episodes, ProposalRepository proposals, HypothesisRepository hypotheses,
                                 SourcingSettings settings, DecisionRecordRepository decisionRecords, LearnerGovernance governance) {
        this.plans = plans;
        this.episodes = episodes;
        this.proposals = proposals;
        this.hypotheses = hypotheses;
        this.settings = settings;
        this.decisionRecords = decisionRecords;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${oms.learning.fs04.sweep-ms:604800000}", initialDelayString = "${oms.learning.fs04.sweep-ms:1000000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Sourcing weight sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        if (!governance.begin(KEY).mayPropose()) return 0;
        governance.recordRun(KEY);
        if (proposals.existsOpenFor(TARGET)) return 0;
        List<double[]> rows = new ArrayList<>(); // cost, time, soft, split, reward
        for (var e : plans.chosenComponentsSince(Instant.now().minus(Duration.ofDays(windowDays)), 5_000)) {
            Double reward = episodes.forOrder(e.getKey()).stream().filter(ep -> SoftScorer.DECISION.equals(ep.decisionName()) && ep.reward() != null)
                .map(Episode::reward).findFirst().orElse(null);
            if (reward == null) continue;
            Map<String, Double> c = e.getValue();
            rows.add(new double[]{c.get("cost"), c.get("time"), c.get("soft"), c.get("split"), reward});
        }
        if (rows.size() < minSamples) return 0;
        Map<String, Double> current = new LinkedHashMap<>();
        var w = settings.weights();
        current.put("cost", w.cost()); current.put("time", w.time()); current.put("soft", w.soft()); current.put("split", w.split());
        Map<String, Double> corr = correlations(rows);
        Map<String, Double> proposed = propose(current, corr, maxStep);
        List<PolicyChange> changes = new ArrayList<>();
        for (String k : COMPONENTS) {
            if (Math.abs(proposed.get(k) - current.get(k)) >= 0.005) changes.add(new PolicyChange.WeightChange(SourcingConfigKeys.WEIGHTS, k, round(current.get(k)), round(proposed.get(k))));
        }
        if (changes.isEmpty()) return 0;
        List<String> evidence = new ArrayList<>();
        evidence.add(rows.size() + " sourced orders with a rewarded episode in the last " + windowDays + " days");
        for (String k : COMPONENTS) evidence.add(String.format(Locale.ROOT, "%s component vs reward correlation %.3f (weight %.3f -> %.3f)", k, corr.get(k), current.get(k), proposed.get(k)));
        Instant now = Instant.now();
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("target", TARGET), "reward", Direction.INCREASE,
            Map.of("action", "reweight", "target", TARGET, "proposed", proposed), 0.5, evidence,
            "Plan-score weights should follow the components that go with better outcomes.", Hypothesis.Status.DRAFTED, now);
        hypotheses.insert(h);
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("config", SourcingConfigKeys.WEIGHTS), 0, changes, h.hypothesisId(), PolicyChangeset.RiskTier.T1);
        proposals.insert(new Proposal(Ids.prefixed("prop"), cs, h.hypothesisId(), PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null,
            Actor.system(KEY).ref(), now, now, null, null, null, null, null, null, null, null));
        log.info("FS-04 proposal for {}: {} -> {}", TARGET, current, proposed);
        return 1;
    }

    /** Pearson correlation of each component with the reward; 0 when either has no spread. */
    static Map<String, Double> correlations(List<double[]> rows) {
        int n = rows.size();
        double[] mean = new double[5];
        for (double[] r : rows) for (int i = 0; i < 5; i++) mean[i] += r[i] / n;
        Map<String, Double> out = new LinkedHashMap<>();
        for (int k = 0; k < 4; k++) {
            double cov = 0, vx = 0, vy = 0;
            for (double[] r : rows) {
                double dx = r[k] - mean[k], dy = r[4] - mean[4];
                cov += dx * dy; vx += dx * dx; vy += dy * dy;
            }
            out.put(COMPONENTS.get(k), vx <= 1e-12 || vy <= 1e-12 ? 0.0 : cov / Math.sqrt(vx * vy));
        }
        return out;
    }

    /** Each weight moves by correlation x maxStep (so at most maxStep), floored at 0.02, then all are renormalised. */
    static Map<String, Double> propose(Map<String, Double> current, Map<String, Double> corr, double maxStep) {
        Map<String, Double> moved = new LinkedHashMap<>();
        double total = 0;
        for (String k : COMPONENTS) {
            double v = Math.max(0.02, current.get(k) + corr.getOrDefault(k, 0.0) * maxStep);
            moved.put(k, v);
            total += v;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String k : COMPONENTS) out.put(k, round(moved.get(k) / total));
        return out;
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000d; }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Sourcing weights", "Suggests how much cost, speed, node quality and fewer packages should count when choosing a plan.",
            LearnerDescriptor.Loop.FAST, "FS-04").readsOutcomesOf(SoftScorer.DECISION).features("plan.cost", "plan.time", "plan.soft", "plan.split");
    }
}
