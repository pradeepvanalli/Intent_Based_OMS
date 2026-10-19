package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
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
 * FD-07: pauses the fraud learners when what they learn from stops looking like what they learned on, so a sale day
 * or a new fraud pattern is not mistaken for noise and tuned against.
 * <ul>
 *   <li><b>Feature drift.</b> For {@code payment_risk}, the population stability index (PSI) of each input feature this
 *       week against the prior four weeks (velocity, address/device/card linkage, billing mismatch, order value). The
 *       largest PSI is recorded as a {@code FEATURE_DRIFT_PSI} fact on the decision; above
 *       {@code oms.learning.fraud-drift-psi} (default 0.25, the usual "significant shift" line) the learners pause.</li>
 *   <li><b>Label drift.</b> For every fraud decision, a confirmed-fraud rate this week at least
 *       {@code oms.learning.fraud-drift-multiple} (default 2) times the baseline, or under half of it.</li>
 * </ul>
 * Pausing goes through {@link LearnerGovernance}, with the reason, so autonomy levels are kept and a person resumes.
 * Every pause also raises a high-severity operator alert ({@code learning:fraud_drift:<tenant>}) through
 * {@link OperatorAlerts}, so someone looks at it rather than finding it in a digest. Labels are read as
 * {@link FraudLabels} says: a provisional "not fraud" inside the chargeback window is not counted.
 */
@Component
public class FraudDriftMonitor {
    private static final Logger log = LoggerFactory.getLogger(FraudDriftMonitor.class);
    private static final List<String> FRAUD_DECISIONS = List.of("payment_risk", "rto_risk", "address_risk", "return_risk");
    private static final List<String> GUARDED_LEARNERS = List.of(FraudThresholdTuner.KEY, FraudAbuseHypothesisScanner.KEY, FraudSignalLearner.KEY);
    static final List<String> PSI_FEATURES = List.of("payment.velocity_1h", "payment.velocity_24h", "payment.address_linkage_count", "payment.device_linkage_count",
        "payment.token_customer_count", "payment.billing_shipping_mismatch", "order.total");
    static final int MIN_RECORDS = 50;

    private final DecisionRecordRepository decisionRecords;
    private final LearnedFactRepository facts;
    private final LearnerGovernance governance;
    private final org.springframework.beans.factory.ObjectProvider<OperatorAlerts> alerts;
    private final ai.intentoms.platform.config.TenantConfigStore config;

    @Value("${oms.learning.fraud-drift-multiple:2.0}") private double driftMultiple = 2.0;
    @Value("${oms.learning.fraud-drift-psi:0.25}") private double psiThreshold = 0.25;

    public FraudDriftMonitor(DecisionRecordRepository decisionRecords, LearnedFactRepository facts, LearnerGovernance governance,
                             org.springframework.beans.factory.ObjectProvider<OperatorAlerts> alerts, ai.intentoms.platform.config.TenantConfigStore config) {
        this.governance = governance;
        this.alerts = alerts;
        this.config = config;
        this.decisionRecords = decisionRecords;
        this.facts = facts;
    }

    @Scheduled(fixedDelayString = "${oms.learning.fraud-drift-sweep-ms:86400000}", initialDelayString = "${oms.learning.fraud-drift-sweep-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Fraud drift sweep failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(35))));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::checkTenant);
    }

    /** Result of one check, for tests and the run endpoint: max PSI per decision checked and why learners paused. */
    public record DriftReport(Map<String, Double> featurePsi, String pausedFor) {}

    public DriftReport checkTenant() {
        Instant now = Instant.now();
        Instant weekAgo = now.minus(Duration.ofDays(7));
        Instant baselineStart = now.minus(Duration.ofDays(35));
        String reason = null;

        Map<String, Double> psi = featurePsi(records("payment_risk", baselineStart, weekAgo), records("payment_risk", weekAgo, now));
        if (!psi.isEmpty()) {
            var worst = psi.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            recordPsi("payment_risk", worst.getValue());
            if (worst.getValue() > psiThreshold) reason = String.format(Locale.ROOT, "fraud feature drift: %s PSI %.2f on payment_risk", worst.getKey(), worst.getValue());
        }
        for (String decision : FRAUD_DECISIONS) {
            if (reason != null) break;
            Double baseline = confirmedRate(decision, baselineStart, weekAgo);
            Double recent = confirmedRate(decision, weekAgo, now);
            if (baseline == null || recent == null || baseline < 0.01) continue;
            double ratio = recent / baseline;
            if (ratio >= driftMultiple || ratio <= 1.0 / driftMultiple)
                reason = String.format(Locale.ROOT, "fraud label drift: confirmed rate on %s moved from %.1f%% to %.1f%%", decision, baseline * 100, recent * 100);
        }
        if (reason != null) {
            log.warn("{}; pausing fraud learners", reason);
            List<String> paused = new ArrayList<>();
            for (String key : GUARDED_LEARNERS) if (governance.gate(key).mayRun()) { governance.pause(key, reason, "system:fraud-drift-monitor"); paused.add(key); }
            alert(reason, paused);
        }
        return new DriftReport(psi, reason);
    }

    private void alert(String reason, List<String> paused) {
        OperatorAlerts sink = alerts.getIfAvailable();
        if (sink == null) return;
        String body = reason + ". " + (paused.isEmpty() ? "The fraud learners were already paused." : "Paused auto-tuning: " + String.join(", ", paused) + ".")
            + " Check whether this is a real shift (a sale, a new fraud pattern, a data problem) before resuming them.";
        try {
            sink.raise("learning:fraud_drift:" + TenantContext.require(), "high", "Fraud learning paused: drift detected", body, Map.of("learning", "/learning"));
        } catch (RuntimeException e) {
            log.warn("Fraud drift alert failed: {}", e.toString());
        }
    }

    /** PSI per feature, recent against baseline; empty when either window has too few decisions to judge. */
    static Map<String, Double> featurePsi(List<Map<String, Object>> baseline, List<Map<String, Object>> recent) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (baseline.size() < MIN_RECORDS || recent.size() < MIN_RECORDS) return out;
        for (String f : PSI_FEATURES) out.put(f, psi(histogram(baseline, f), histogram(recent, f)));
        return out;
    }

    /** Population stability index over shared buckets, with a small floor so an empty bucket does not divide by zero. */
    static double psi(double[] expected, double[] actual) {
        double e = 0, a = 0;
        for (double x : expected) e += x;
        for (double x : actual) a += x;
        if (e == 0 || a == 0) return 0;
        double s = 0;
        for (int i = 0; i < expected.length; i++) {
            double pe = Math.max(expected[i] / e, 1e-4), pa = Math.max(actual[i] / a, 1e-4);
            s += (pa - pe) * Math.log(pa / pe);
        }
        return Math.round(s * 10000) / 10000d;
    }

    /** Counts become buckets 0, 1, 2, 3+; a flag false/true; order value 0-50-100-250-500-1000+. */
    static double[] histogram(List<Map<String, Object>> inputs, String feature) {
        boolean money = "order.total".equals(feature);
        double[] h = new double[money ? 6 : 4];
        for (Map<String, Object> in : inputs) {
            Object v = Json.path(in, feature);
            if (v == null) v = in.get(feature);
            double x = v instanceof Boolean b ? (b ? 1 : 0) : v instanceof Number n ? n.doubleValue() : 0;
            int bucket = money ? (x < 50 ? 0 : x < 100 ? 1 : x < 250 ? 2 : x < 500 ? 3 : x < 1000 ? 4 : 5) : (int) Math.min(3, Math.max(0, Math.floor(x)));
            h[bucket]++;
        }
        return h;
    }

    private List<Map<String, Object>> records(String decision, Instant from, Instant to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int[] v : decisionRecords.activeVersions(decision, from)) {
            for (DecisionRecord r : decisionRecords.recentLive(decision, v[0], from, 20_000)) {
                if (r.createdAt() != null && !r.createdAt().isBefore(to)) continue;
                if (r.input() != null) out.add(r.input());
            }
        }
        return out;
    }

    private void recordPsi(String decision, double value) {
        Instant now = Instant.now();
        var prev = facts.active(SubjectType.DECISION, decision, FactKey.FEATURE_DRIFT_PSI);
        facts.supersede(SubjectType.DECISION, decision, FactKey.FEATURE_DRIFT_PSI, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.DECISION, decision, FactKey.FEATURE_DRIFT_PSI, new TypedValue.NumberValue(value),
            1.0, 0, "fraud_drift_monitor", prev.map(f -> f.version() + 1).orElse(1), now, null, now));
    }

    @SuppressWarnings("unchecked")
    private Double confirmedRate(String decision, Instant from, Instant to) {
        List<DecisionRecord> labelled = new ArrayList<>();
        for (int[] v : decisionRecords.activeVersions(decision, from)) labelled.addAll(decisionRecords.labelledBetween(decision, v[0], from, to));
        Instant now = Instant.now();
        int window = config.getInt(FraudLabels.WINDOW_KEY, FraudLabels.DEFAULT_WINDOW_DAYS);
        List<Boolean> usable = labelled.stream().map(r -> FraudLabels.usable(r, now, window)).filter(java.util.Objects::nonNull).toList();
        if (usable.size() < 20) return null;
        long confirmed = usable.stream().filter(Boolean::booleanValue).count();
        return (double) confirmed / usable.size();
    }
}
