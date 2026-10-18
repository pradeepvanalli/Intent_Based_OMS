package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * DF-01: tunes band thresholds per decision to maximize accuracy within an escalation budget. Reads the existing
 * golden-set evaluation's calibration bins ({@link DecisionStudioService#evaluate}, a read/compute-only call — it
 * never publishes) to find the lowest confidence threshold whose bins from there upward all clear the decision's
 * own {@code minAccuracyInActBand}, and proposes moving the act threshold there when that differs meaningfully
 * from the current one. Never calls {@code DecisionStudioService.edit()}/{@code publish()} — only ever writes a
 * {@link Proposal} for a human to act on through the existing studio.
 */
@Component
public class BandThresholdLearner implements GovernedLearner {
    public static final String KEY = "band_threshold";
    private static final Logger log = LoggerFactory.getLogger(BandThresholdLearner.class);
    private static final String LEARNER_KEY = "band_threshold";
    private static final int MIN_BIN_SAMPLE = 10;

    private final DecisionDefinitionRepository definitions;
    private final DecisionStudioService studio;
    private final DecisionRecordRepository decisionRecords;
    private final ProposalRepository proposals;

    @Value("${oms.learning.df01.max-step:0.05}") private double maxStep;
    @Value("${oms.learning.df01.min-move:0.02}") private double minMove;

    private final LearnerGovernance governance;

    public BandThresholdLearner(DecisionDefinitionRepository definitions, DecisionStudioService studio, DecisionRecordRepository decisionRecords, ProposalRepository proposals, LearnerGovernance governance) {
        this.governance = governance;
        this.definitions = definitions;
        this.studio = studio;
        this.decisionRecords = decisionRecords;
        this.proposals = proposals;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df01.sweep-ms:86400000}", initialDelayString = "${oms.learning.df01.sweep-ms:600000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Band-threshold sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int proposed = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(java.time.Duration.ofDays(30))));
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

    private boolean sweepDecision(String name) {
        List<DecisionDefinitionRepository.Row> live = definitions.byStatus(name, "live");
        if (live.isEmpty()) return false;
        DecisionDefinition def = live.get(0).definition();
        if (def.evaluation() == null || def.tier() == Tier.T3) return false; // T3 never acts; nothing to tune
        if (hasPendingProposal(name)) return false;

        EvaluationReport report = studio.evaluate(name, def.version());
        if (report.bins() == null || report.bins().isEmpty()) return false;
        double minAccuracy = def.evaluation().minAccuracyInActBand();
        double currentAct = def.effectiveBands().actGte();

        // Lowest bin boundary from which every higher bin (with enough sample) clears the accuracy bar.
        Double candidate = null;
        for (var bin : report.bins()) {
            if (bin.count() < MIN_BIN_SAMPLE) continue;
            boolean restClear = report.bins().stream().filter(b -> b.lower() >= bin.lower() && b.count() >= MIN_BIN_SAMPLE).allMatch(b -> b.accuracy() >= minAccuracy);
            if (restClear) { candidate = bin.lower(); break; }
        }
        if (candidate == null) return false; // no bin range clears the bar; don't propose anything

        double proposedAct = clampStep(currentAct, candidate);
        if (Math.abs(proposedAct - currentAct) < minMove) return false;

        PolicyChange change = new PolicyChange.ThresholdChange(name, "act", round(currentAct), round(proposedAct));
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", name), def.version(), List.of(change),
            null, mapTier(def.tier()));
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, null, mapTier(def.tier()), ProposalStatus.DRAFT, null, null,
            Actor.system(LEARNER_KEY).ref(), Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        log.info("DF-01 proposal {}: {} act threshold {} -> {} (calibration bins clear {} from {})", p.proposalId(), name, round(currentAct), round(proposedAct), minAccuracy, candidate);
        return true;
    }

    /** GV-02: cap movement per proposal to the learner's max step, moving toward the candidate either direction. */
    private double clampStep(double current, double candidate) {
        double delta = candidate - current;
        double capped = Math.max(-maxStep, Math.min(maxStep, delta));
        double result = current + capped;
        return Math.max(0, Math.min(1, result));
    }

    private boolean hasPendingProposal(String name) { return proposals.existsOpenFor("decision:" + name); }

    private static PolicyChangeset.RiskTier mapTier(Tier t) { return PolicyChangeset.RiskTier.valueOf(t.name()); }
    private static double round(double d) { return Math.round(d * 1000) / 1000d; }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Confidence band tuner", "Suggests where each decision's act and confirm thresholds should sit so accuracy holds within the escalation budget.", LearnerDescriptor.Loop.FAST, "DF-01");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
