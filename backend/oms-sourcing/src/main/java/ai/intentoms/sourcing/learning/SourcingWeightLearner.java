package ai.intentoms.sourcing.learning;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.WeightLever;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
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
 * it, each by at most {@code maxStep} per proposal, and the vector is renormalised to sum to 1. The result is a typed
 * hypothesis ({@link WeightLever}: current and suggested weights, correlations, sample size) that the Author turns into
 * a {@code WeightChange} proposal (T1) on {@code config:sourcing.weights}; it is approved and applied through the normal
 * promotion gate, never written directly.
 *
 * <p>Validated by replay ({@link SourcingWeightReplay}, served to the Author as {@link WeightLever.Evidence}): every
 * recorded order's considered plans are re-scored under the proposed weights, and the orders whose chosen plan would
 * change are scored with a reward model fitted on the chosen plans. That report, with its 95% interval, is the
 * proposal's evaluation ({@code plan_rescoring_replay}).
 */
@Component
public class SourcingWeightLearner implements GovernedLearner, WeightLever.Evidence {
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
    private final LearningEvents events;

    @Value("${oms.learning.fs04.replay-orders:1000}") private int replayOrders = 1000;
    @Value("${oms.learning.fs04.window-days:60}") private int windowDays = 60;
    @Value("${oms.learning.fs04.min-samples:50}") private int minSamples = 50;
    @Value("${oms.learning.fs04.max-step:0.05}") private double maxStep = 0.05;

    public SourcingWeightLearner(SourcingPlanRepository plans, EpisodeRepository episodes, ProposalRepository proposals, HypothesisRepository hypotheses,
                                 SourcingSettings settings, DecisionRecordRepository decisionRecords, LearnerGovernance governance, LearningEvents events) {
        this.events = events;
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
        if (proposals.existsOpenFor(TARGET) || openHypothesis()) return 0;
        List<double[]> rows = new ArrayList<>(); // cost, time, soft, split, reward
        rewardedOrders(rows);
        if (rows.size() < minSamples) return 0;
        Map<String, Double> current = liveWeights();
        Map<String, Double> corr = correlations(rows);
        Map<String, Double> proposed = propose(current, corr, maxStep);
        WeightLever lever = new WeightLever(SourcingConfigKeys.WEIGHTS, current, proposed, corr, rows.size(), windowDays);
        if (lever.changes().isEmpty()) return 0;
        List<String> evidence = new ArrayList<>();
        evidence.add(rows.size() + " sourced orders with a rewarded episode in the last " + windowDays + " days");
        for (String k : COMPONENTS) evidence.add(String.format(Locale.ROOT, "%s component vs reward correlation %.3f (weight %.3f -> %.3f)", k, corr.get(k), current.get(k), proposed.get(k)));
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("target", TARGET), "reward", Direction.INCREASE, lever.toLever(), 0.5, evidence,
            "Plan-score weights should follow the components that go with better outcomes.", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        log.info("FS-04 hypothesis {} for {}: {} -> {} ({} rewarded orders)", h.hypothesisId(), TARGET, current, proposed, rows.size());
        return 1;
    }

    private boolean openHypothesis() {
        return hypotheses.byStatus("OPEN", 500).stream().anyMatch(h -> WeightLever.of(h).filter(l -> TARGET.equals(l.target())).isPresent());
    }

    /** Chosen-plan components paired with the order's rewarded sourcing episode; fills {@code rows} and returns reward by order. */
    private Map<String, Double> rewardedOrders(List<double[]> rows) {
        Map<String, Double> rewardByOrder = new LinkedHashMap<>();
        for (var e : plans.chosenComponentsSince(Instant.now().minus(Duration.ofDays(windowDays)), 5_000)) {
            Double reward = episodes.forOrder(e.getKey()).stream().filter(ep -> SoftScorer.DECISION.equals(ep.decisionName()) && ep.reward() != null)
                .map(Episode::reward).findFirst().orElse(null);
            if (reward == null) continue;
            Map<String, Double> c = e.getValue();
            if (rows != null) rows.add(new double[]{c.get("cost"), c.get("time"), c.get("soft"), c.get("split"), reward});
            rewardByOrder.put(e.getKey(), reward);
        }
        return rewardByOrder;
    }

    // ---- WeightLever.Evidence: what the Author attaches to the proposal it drafts from the hypothesis ----------------

    @Override public boolean supports(String configKey) { return SourcingConfigKeys.WEIGHTS.equals(configKey); }

    @Override
    public Map<String, Double> liveWeights() {
        Map<String, Double> current = new LinkedHashMap<>();
        var w = settings.weights();
        current.put("cost", round(w.cost())); current.put("time", round(w.time())); current.put("soft", round(w.soft())); current.put("split", round(w.split()));
        return current;
    }

    /** The {@code plan_rescoring_replay} for a move from {@code current} to {@code proposed}, over the same window of rewarded orders. */
    @Override
    public Map<String, Object> report(Map<String, Double> current, Map<String, Double> proposed) {
        Map<String, Object> report = new LinkedHashMap<>(SourcingWeightReplay.replay(replayOrders(rewardedOrders(null)), current, proposed));
        report.put("changeTypes", List.of("WeightChange"));
        report.put("evaluatedAt", Instant.now().toString());
        return report;
    }

    /** The recorded orders to replay: every considered plan with its components, the chosen one marked, and the reward. */
    @SuppressWarnings("unchecked")
    private List<SourcingWeightReplay.Order> replayOrders(Map<String, Double> rewardByOrder) {
        List<SourcingWeightReplay.Order> out = new ArrayList<>();
        for (var e : rewardByOrder.entrySet()) {
            if (out.size() >= replayOrders) break;
            List<SourcingWeightReplay.Plan> considered = new ArrayList<>();
            for (SourcingPlanRepository.Row row : plans.forOrder(e.getKey())) {
                Object explain = row.plan().get("explain");
                Object comps = explain instanceof Map<?, ?> m ? m.get("components") : null;
                if (!(comps instanceof Map<?, ?> cm)) continue;
                Map<String, Double> c = new LinkedHashMap<>();
                for (String k : SourcingWeightReplay.COMPONENTS) if (cm.get(k) instanceof Number n) c.put(k, n.doubleValue());
                considered.add(new SourcingWeightReplay.Plan(row.id(), row.policy(), c, row.chosen()));
            }
            out.add(new SourcingWeightReplay.Order(e.getKey(), considered, e.getValue()));
        }
        return out;
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
