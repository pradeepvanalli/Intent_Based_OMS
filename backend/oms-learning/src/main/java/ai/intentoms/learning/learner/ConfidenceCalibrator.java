package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DF-02: calibrates L1 confidence per decision — maps raw model confidence to observed accuracy. Reuses the
 * golden-set evaluation's {@code Calibration.compute} output (via {@link DecisionStudioService#evaluate}) rather
 * than reimplementing the reliability-curve math, and stores the result as a versioned {@link LearnedFact} per
 * decision: {@link FactKey#CALIBRATION_ERROR} (scalar, for trend monitoring and the override-rate/calibration KPI)
 * and {@link FactKey#CALIBRATION_CURVE} (the per-bin correction a future confidence-remapping consumer can apply).
 * Observe-only: writes a fact, never changes how a decision actually runs.
 */
@Component
public class ConfidenceCalibrator implements GovernedLearner {
    public static final String KEY = "confidence_calibrator";
    private static final Logger log = LoggerFactory.getLogger(ConfidenceCalibrator.class);
    private static final String LEARNER = "confidence_calibrator";

    private final DecisionDefinitionRepository definitions;
    private final DecisionStudioService studio;
    private final DecisionRecordRepository decisionRecords;
    private final LearnedFactRepository facts;

    private final LearnerGovernance governance;

    public ConfidenceCalibrator(DecisionDefinitionRepository definitions, DecisionStudioService studio, DecisionRecordRepository decisionRecords, LearnedFactRepository facts, LearnerGovernance governance) {
        this.governance = governance;
        this.definitions = definitions;
        this.studio = studio;
        this.decisionRecords = decisionRecords;
        this.facts = facts;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df02.sweep-ms:86400000}", initialDelayString = "${oms.learning.df02.sweep-ms:650000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Calibration sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int n = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(java.time.Duration.ofDays(30))));
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        int n = 0;
        for (String name : definitions.names()) {
            try { if (sweepDecision(name)) n++; } catch (RuntimeException e) { log.warn("Calibration check failed for {}: {}", name, e.toString()); }
        }
        return n;
    }

    private boolean sweepDecision(String name) {
        var live = definitions.byStatus(name, "live");
        if (live.isEmpty()) return false;
        DecisionDefinition def = live.get(0).definition();
        if (def.evaluation() == null) return false;
        EvaluationReport report = studio.evaluate(name, def.version());
        if (report.bins().isEmpty()) return false;

        writeFact(name, FactKey.CALIBRATION_ERROR, new TypedValue.NumberValue(report.calibrationError()), 1.0, report.cases());

        List<String> curve = new ArrayList<>();
        for (var bin : report.bins()) {
            if (bin.count() == 0) continue;
            double correction = bin.accuracy() - bin.meanConfidence();
            curve.add(String.format(Locale.ROOT, "%.2f-%.2f:%+.3f", bin.lower(), bin.upper(), correction));
        }
        if (!curve.isEmpty()) writeFact(name, FactKey.CALIBRATION_CURVE, new TypedValue.ListValue(curve), 1.0, report.cases());
        return true;
    }

    private void writeFact(String decisionName, FactKey key, TypedValue value, double confidence, int sampleSize) {
        Instant now = Instant.now();
        var prior = facts.active(SubjectType.DECISION, decisionName, key);
        int version = prior.map(f -> f.version() + 1).orElse(1);
        facts.supersede(SubjectType.DECISION, decisionName, key, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.DECISION, decisionName, key, value, confidence, sampleSize, LEARNER, version, now, null, now));
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Confidence calibration", "Checks whether a decision that says 90% sure is right 90% of the time, and corrects it when not.", LearnerDescriptor.Loop.FAST, "DF-02").feeds("decision.calibration");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
