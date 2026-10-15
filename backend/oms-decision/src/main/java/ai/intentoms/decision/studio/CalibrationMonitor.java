package ai.intentoms.decision.studio;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DriftReport;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weekly calibration and drift monitoring (GR-12). For every decision version with LIVE records in the last two weeks
 * it computes accuracy per confidence bin from labelled records (label {@code correct: {value: true|false}} or
 * {@code outcome: {value: X}} compared with the decision value, GR-13 overrides count as incorrect) and the band-mix
 * shift against the previous week. A calibration error above the definition's {@code maxCalibrationError} (default
 * 0.05) or a band shift above 20 points raises {@code decision.drift_detected} and a metric.
 */
@Component
public class CalibrationMonitor {
    public static final double BAND_SHIFT_POINTS = 20.0;
    public static final double DEFAULT_MAX_CALIBRATION_ERROR = 0.05;
    private static final Logger log = LoggerFactory.getLogger(CalibrationMonitor.class);
    private final DecisionRecordRepository records;
    private final DecisionDefinitionRepository definitions;
    private final EventStore events;
    private final Metrics metrics;
    private final TimeSource time;

    public CalibrationMonitor(DecisionRecordRepository records, DecisionDefinitionRepository definitions, EventStore events, Metrics metrics, TimeSource time) {
        this.records = records;
        this.definitions = definitions;
        this.events = events;
        this.metrics = metrics;
        this.time = time;
    }

    /** Monday 06:00 UTC, every tenant with recent records. */
    @Scheduled(cron = "0 0 6 * * MON", zone = "UTC")
    public void weekly() {
        Instant since = time.now().minus(Duration.ofDays(14));
        for (String tenant : records.tenantsWithRecords(since)) {
            try { TenantContext.runAs(tenant, this::run); }
            catch (RuntimeException e) { log.error("Calibration monitoring failed for tenant {}", tenant, e); }
        }
    }

    /** Runs the check for the current tenant and returns one report per decision version examined. */
    public List<DriftReport> run() {
        Instant now = time.now();
        Instant since = now.minus(Duration.ofDays(14));
        List<DriftReport> out = new ArrayList<>();
        for (String decision : records.decisionsWithRecords(since)) {
            for (int[] v : records.activeVersions(decision, since)) out.add(check(decision, v[0]));
        }
        return out;
    }

    public DriftReport check(String decision, int version) {
        Instant now = time.now();
        Instant weekAgo = now.minus(Duration.ofDays(7));
        Instant twoWeeksAgo = now.minus(Duration.ofDays(14));
        List<DecisionRecord> labelled = records.labelledBetween(decision, version, weekAgo, now);
        List<Calibration.Observation> obs = new ArrayList<>();
        for (DecisionRecord r : labelled) correctness(r).ifPresent(c -> obs.add(new Calibration.Observation(r.confidence(), c)));
        Calibration.Result cal = Calibration.compute(obs);
        double max = definitions.find(decision, version).map(row -> row.definition().evaluation()).map(e -> e.maxCalibrationError()).orElse(DEFAULT_MAX_CALIBRATION_ERROR);

        Map<String, Integer> thisWeek = records.bandMixBetween(decision, version, weekAgo, now);
        Map<String, Integer> lastWeek = records.bandMixBetween(decision, version, twoWeeksAgo, weekAgo);
        Map<String, Double> pctThis = percentages(thisWeek), pctLast = percentages(lastWeek);
        double shift = 0;
        boolean bothWeeks = total(thisWeek) > 0 && total(lastWeek) > 0;
        if (bothWeeks) for (Band b : Band.values()) shift = Math.max(shift, Math.abs(pctThis.get(b.name()) - pctLast.get(b.name())));

        List<String> reasons = new ArrayList<>();
        if (!obs.isEmpty() && cal.error() > max) reasons.add(String.format(java.util.Locale.ROOT, "calibration error %.3f exceeds %.3f over %d labelled records", cal.error(), max, obs.size()));
        if (bothWeeks && shift > BAND_SHIFT_POINTS) reasons.add(String.format(java.util.Locale.ROOT, "band mix shifted %.1f points week over week", shift));
        boolean drift = !reasons.isEmpty();
        DriftReport report = new DriftReport(decision, version, obs.size(), cal.error(), max, cal.bins(), pctThis, pctLast, Calibration.round(shift), drift, reasons);
        if (drift) {
            metrics.count("oms.decision.drift", "decision", decision);
            events.append("config", "decision:" + decision, new NewEvent("decision.drift_detected", Map.of("decision", decision, "version", version, "labelled", obs.size(),
                "calibrationError", cal.error(), "maxCalibrationError", max, "bandShiftPoints", Calibration.round(shift), "reasons", reasons), Actor.system("calibration-monitor")));
            log.warn("decision.drift_detected {}@{}: {}", decision, version, reasons);
        }
        return report;
    }

    /** Derives correctness from labels: an override is wrong by definition; "correct" wins; else "outcome" compared with the value. */
    @SuppressWarnings("unchecked")
    static java.util.Optional<Boolean> correctness(DecisionRecord r) {
        if (r.overriddenAt() != null) return java.util.Optional.of(false);
        Object c = r.labels().get("correct");
        if (c != null) return java.util.Optional.of(truthy(c instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("value") : c));
        Object o = r.labels().get("outcome");
        if (o != null) {
            Object v = o instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("value") : o;
            return java.util.Optional.of(Calibration.valueEquals(v, r.value()));
        }
        return java.util.Optional.empty();
    }

    private static boolean truthy(Object v) { return v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v)); }

    private static int total(Map<String, Integer> m) { return m.values().stream().mapToInt(Integer::intValue).sum(); }

    private static Map<String, Double> percentages(Map<String, Integer> counts) {
        int t = total(counts);
        Map<String, Double> out = new LinkedHashMap<>();
        for (Band b : Band.values()) out.put(b.name(), t == 0 ? 0.0 : Calibration.round(100.0 * counts.getOrDefault(b.name(), 0) / t));
        return out;
    }
}
