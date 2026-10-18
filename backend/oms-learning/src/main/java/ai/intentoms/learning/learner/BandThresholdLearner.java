package ai.intentoms.learning.learner;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

/**
 * DF-01: tunes each decision's act threshold to maximise accuracy within an escalation budget. Inputs are the golden-set
 * calibration bins ({@link DecisionStudioService#evaluate}, read-only) and the confidence of the last 14 days of live
 * decisions, which estimates how many decisions per day would not act (and so go to a person) at a given threshold.
 *
 * <p>With a tenant budget ({@code learning.escalation_budget_per_day}), the learner picks, among thresholds whose act-band
 * accuracy clears the decision's {@code minAccuracyInActBand} and whose expected escalations fit the budget, the one with
 * the highest act-band accuracy (ties: the lower threshold, more automation). Without a budget it picks the lowest
 * threshold from which every bin clears the accuracy bar. The move is capped at the learner's max step (GV-02) and
 * written as a proposal; it never edits or publishes the decision itself.
 */
@Component
public class BandThresholdLearner implements GovernedLearner {
    public static final String KEY = "band_threshold";
    public static final String BUDGET_KEY = "learning.escalation_budget_per_day";
    private static final Logger log = LoggerFactory.getLogger(BandThresholdLearner.class);
    private static final int MIN_BIN_SAMPLE = 10;
    private static final Duration TRAFFIC_WINDOW = Duration.ofDays(14);

    private final DecisionDefinitionRepository definitions;
    private final DecisionStudioService studio;
    private final DecisionRecordRepository decisionRecords;
    private final ProposalRepository proposals;
    private final TenantConfigStore config;
    private final LearnerGovernance governance;

    @Value("${oms.learning.df01.max-step:0.05}") private double maxStep;
    @Value("${oms.learning.df01.min-move:0.02}") private double minMove;

    public BandThresholdLearner(DecisionDefinitionRepository definitions, DecisionStudioService studio, DecisionRecordRepository decisionRecords, ProposalRepository proposals,
                                TenantConfigStore config, LearnerGovernance governance) {
        this.definitions = definitions;
        this.studio = studio;
        this.decisionRecords = decisionRecords;
        this.proposals = proposals;
        this.config = config;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df01.sweep-ms:86400000}", initialDelayString = "${oms.learning.df01.sweep-ms:600000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Band-threshold sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int proposed = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(30))));
        for (String tenant : tenants) proposed += TenantContext.supplyAs(tenant, this::sweepTenant);
        return proposed;
    }

    private int sweepTenant() {
        if (!governance.begin(KEY).mayPropose()) return 0;
        governance.recordRun(KEY);
        int n = 0;
        for (String name : definitions.names()) {
            try { if (sweepDecision(name)) n++; } catch (RuntimeException e) { log.warn("Band-threshold check failed for {}: {}", name, e.toString()); }
        }
        return n;
    }

    /** One candidate act threshold and what it would mean. */
    public record Candidate(double threshold, double actAccuracy, int actCases, double escalationsPerDay) {}

    private boolean sweepDecision(String name) {
        List<DecisionDefinitionRepository.Row> live = definitions.byStatus(name, "live");
        if (live.isEmpty()) return false;
        DecisionDefinition def = live.get(0).definition();
        if (def.evaluation() == null || def.tier() == Tier.T3) return false; // T3 never acts; nothing to tune
        if (proposals.existsOpenFor("decision:" + name)) return false;

        EvaluationReport report = studio.evaluate(name, def.version());
        if (report.bins() == null || report.bins().isEmpty()) return false;
        double minAccuracy = def.evaluation().minAccuracyInActBand();
        double currentAct = def.effectiveBands().actGte();
        double budget = config.getDouble(BUDGET_KEY, -1);

        List<Double> confidences = decisionRecords.recentLive(name, def.version(), Instant.now().minus(TRAFFIC_WINDOW), 5000).stream()
            .filter(r -> r.layer() != ai.intentoms.decision.model.Layer.L0_RULE).map(DecisionRecord::confidence).toList();
        double days = TRAFFIC_WINDOW.toDays();
        DoubleUnaryOperator escalationsPerDay = t -> confidences.stream().filter(c -> c < t).count() / days;

        Optional<Candidate> pick = choose(report.bins(), minAccuracy, escalationsPerDay, budget, MIN_BIN_SAMPLE);
        if (pick.isEmpty()) {
            log.info("DF-01 {}: no threshold clears accuracy {}{}", name, minAccuracy, budget > 0 ? " within " + budget + " escalations/day" : "");
            return false;
        }
        double proposedAct = clampStep(currentAct, pick.get().threshold());
        if (Math.abs(proposedAct - currentAct) < minMove) return false;

        String motivation = String.format(Locale.ROOT,
            "Act band from %.3f to %.3f: golden-set act accuracy %.1f%% (floor %.1f%%), about %.1f escalations/day at the new threshold vs %.1f now%s",
            currentAct, proposedAct, pick.get().actAccuracy() * 100, minAccuracy * 100, escalationsPerDay.applyAsDouble(proposedAct), escalationsPerDay.applyAsDouble(currentAct),
            budget > 0 ? String.format(Locale.ROOT, " (budget %.0f/day)", budget) : "");
        PolicyChange change = new PolicyChange.ThresholdChange(name, "act", round(currentAct), round(proposedAct));
        PolicyChangeset.RiskTier tier = PolicyChangeset.RiskTier.valueOf(def.tier().name());
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", name), def.version(), List.of(change), motivation, tier);
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, null, tier, ProposalStatus.DRAFT, null, null,
            Actor.system(KEY).ref(), Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        log.info("DF-01 proposal {}: {}", p.proposalId(), motivation);
        return true;
    }

    /**
     * Pure objective. With {@code budget > 0}: the threshold with the highest act-band accuracy among those that clear
     * {@code minAccuracy} and keep expected escalations within the budget (ties: lower threshold). Without a budget: the
     * lowest threshold from which every bin with enough sample clears {@code minAccuracy}.
     */
    public static Optional<Candidate> choose(List<EvaluationReport.CalibrationBin> bins, double minAccuracy, DoubleUnaryOperator escalationsPerDay, double budget, int minBinSample) {
        List<EvaluationReport.CalibrationBin> sorted = bins.stream().sorted((a, b) -> Double.compare(a.lower(), b.lower())).toList();
        Candidate best = null;
        for (var bin : sorted) {
            if (bin.count() < minBinSample) continue;
            double t = bin.lower();
            List<EvaluationReport.CalibrationBin> above = sorted.stream().filter(b -> b.lower() >= t && b.count() > 0).toList();
            int cases = above.stream().mapToInt(EvaluationReport.CalibrationBin::count).sum();
            if (cases == 0) continue;
            double acc = above.stream().mapToDouble(b -> b.accuracy() * b.count()).sum() / cases;
            boolean everyBinClears = above.stream().filter(b -> b.count() >= minBinSample).allMatch(b -> b.accuracy() >= minAccuracy);
            Candidate c = new Candidate(t, acc, cases, escalationsPerDay.applyAsDouble(t));
            if (budget <= 0) {
                if (everyBinClears) return Optional.of(c);
                continue;
            }
            if (acc < minAccuracy || c.escalationsPerDay() > budget) continue;
            if (best == null || acc > best.actAccuracy() + 1e-9) best = c;
        }
        return Optional.ofNullable(best);
    }

    /** GV-02: cap movement per proposal to the learner's max step, moving toward the candidate either direction. */
    private double clampStep(double current, double candidate) {
        double capped = Math.max(-maxStep, Math.min(maxStep, candidate - current));
        return Math.max(0, Math.min(1, current + capped));
    }

    private static double round(double d) { return Math.round(d * 1000) / 1000d; }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Confidence band tuner", "Suggests where each decision's act threshold should sit for the best accuracy within your escalation budget.", LearnerDescriptor.Loop.FAST, "DF-01");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
