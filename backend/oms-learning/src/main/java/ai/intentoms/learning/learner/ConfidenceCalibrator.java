package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.studio.Calibration;
import ai.intentoms.learning.model.HumanResolution;
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
import java.util.Optional;

/**
 * DF-02: calibrates L1 confidence per decision — maps raw model confidence to observed accuracy. Two sources of
 * observed accuracy share the ten confidence bins of {@code Calibration}: the golden-set evaluation (via
 * {@link DecisionStudioService#evaluate}) and the live decisions people resolved in the last
 * {@code oms.learning.calibration.window-days} (default 90): the model's raw confidence, and whether the value the person
 * settled on ({@link HumanResolution}) is the model's answer. Golden sets are small, so on their own they rarely fill a
 * bin; with the resolutions, curves form in practice. The result is a versioned {@link LearnedFact} per decision:
 * {@link FactKey#CALIBRATION_ERROR} (scalar, for trend monitoring and the override-rate/calibration KPI) and
 * {@link FactKey#CALIBRATION_CURVE} (the per-bin correction, served through {@code decision.calibration}). A bin needs
 * {@code min-per-bin} observations (default 20) and its correction is shrunk and capped at {@code max-correction}
 * (default 0.15).
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

    @org.springframework.beans.factory.annotation.Value("${oms.learning.calibration.min-per-bin:20}") int minPerBin = 20;
    @org.springframework.beans.factory.annotation.Value("${oms.learning.calibration.max-correction:0.15}") double maxCorrection = 0.15;
    @org.springframework.beans.factory.annotation.Value("${oms.learning.calibration.window-days:90}") int windowDays = 90;

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
        List<EvaluationReport.CalibrationBin> golden = def.evaluation() == null ? List.of() : studio.evaluate(name, def.version()).bins();
        List<EvaluationReport.CalibrationBin> bins = merge(golden, resolved(name, def.version()));
        int cases = bins.stream().mapToInt(EvaluationReport.CalibrationBin::count).sum();
        if (cases == 0) return false;

        writeFact(name, FactKey.CALIBRATION_ERROR, new TypedValue.NumberValue(error(bins, cases)), 1.0, cases);

        List<String> curve = new ArrayList<>();
        for (var bin : bins) {
            // DF-02: a bin with too few cases gets no correction at all, and a well-populated one is shrunk toward
            // zero by n/(n+k) and capped, so a handful of golden cases can never move 0.55 to 0.98.
            if (bin.count() < minPerBin) continue;
            double raw = bin.accuracy() - bin.meanConfidence();
            double shrunk = raw * bin.count() / (bin.count() + (double) minPerBin);
            double correction = Math.max(-maxCorrection, Math.min(maxCorrection, shrunk));
            curve.add(String.format(Locale.ROOT, "%.2f-%.2f:%+.3f", bin.lower(), bin.upper(), correction));
        }
        if (!curve.isEmpty()) writeFact(name, FactKey.CALIBRATION_CURVE, new TypedValue.ListValue(curve), 1.0, cases);
        return true;
    }

    /**
     * People's resolutions as observations: the model's raw confidence (before any calibration was applied) and whether
     * the value the person settled on is the model's answer. Only records the model answered count; rules and defaults
     * state no model confidence. That includes escalated records (C-N2): a decision a person resolved was handed to them
     * because of its confidence, so it is stored as L3_HUMAN, but the record keeps the model's answer ({@code value},
     * also {@code details.modelAnswer}) and its confidence ({@code details.rawConfidence} when a curve had moved it), and
     * those are exactly the observations calibration needs. An escalation because the model chose the escape option
     * ("unclear") is not a confident answer and is left out, as is anything a person did not resolve (E3-N1).
     */
    List<Calibration.Observation> resolved(String name, int version) {
        Instant now = Instant.now();
        List<Calibration.Observation> out = new ArrayList<>();
        for (DecisionRecord r : decisionRecords.labelledBetween(name, version, now.minus(java.time.Duration.ofDays(windowDays)), now)) {
            if (!modelAnswered(r)) continue;
            Optional<String> human = HumanResolution.value(r);
            Object model = modelAnswer(r);
            if (human.isEmpty() || model == null) continue;
            double raw = r.details() != null && r.details().get("rawConfidence") instanceof Number n ? n.doubleValue() : r.confidence();
            out.add(new Calibration.Observation(raw, Calibration.valueEquals(human.get(), model))); // numbers at rubric granularity
        }
        return out;
    }

    /** L1/L2 answers, and L3 escalations of a model answer (not of the escape option). */
    static boolean modelAnswered(DecisionRecord r) {
        if (r.layer() == Layer.L1_SYSTEM_ONE || r.layer() == Layer.L2_LLM) return true;
        if (r.layer() != Layer.L3_HUMAN) return false;
        Object reason = r.details() == null ? null : r.details().get("escalationReason");
        if ("escape_option".equals(reason) || (r.reasonCodes() != null && r.reasonCodes().contains("escape_option"))) return false;
        return r.provider() != null;
    }

    /** The model's own answer: kept in {@code details.modelAnswer} when the applied value differs (an escalation, an explored arm). */
    static Object modelAnswer(DecisionRecord r) {
        Object m = r.details() == null ? null : r.details().get("modelAnswer");
        return m != null && !String.valueOf(m).isBlank() ? m : r.value();
    }

    /** Adds observations to the golden-set bins (same ten bins), keeping each bin's count, mean confidence and accuracy. */
    static List<EvaluationReport.CalibrationBin> merge(List<EvaluationReport.CalibrationBin> golden, List<Calibration.Observation> observed) {
        int n = Calibration.BINS;
        double[] count = new double[n], conf = new double[n], correct = new double[n];
        for (var b : golden) {
            int i = (int) Math.min(n - 1, Math.max(0, Math.round(b.lower() * n)));
            count[i] += b.count();
            conf[i] += b.meanConfidence() * b.count();
            correct[i] += b.accuracy() * b.count();
        }
        for (var o : observed) {
            int i = (int) Math.min(n - 1, Math.max(0, Math.floor(o.confidence() * n)));
            count[i]++;
            conf[i] += o.confidence();
            if (o.correct()) correct[i]++;
        }
        List<EvaluationReport.CalibrationBin> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int c = (int) Math.round(count[i]);
            out.add(new EvaluationReport.CalibrationBin(i / (double) n, (i + 1) / (double) n, c, c == 0 ? 0 : conf[i] / count[i], c == 0 ? 0 : correct[i] / count[i]));
        }
        return out;
    }

    /** Count-weighted mean of |mean confidence - accuracy| over the bins. */
    static double error(List<EvaluationReport.CalibrationBin> bins, int total) {
        double e = 0;
        for (var b : bins) if (b.count() > 0) e += (b.count() / (double) total) * Math.abs(b.meanConfidence() - b.accuracy());
        return Math.round(e * 10_000) / 10_000.0;
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
