package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FD-07: detects drift in fraud feature distributions by comparing the confirmed-fraud rate this week against the
 * prior four weeks' baseline for the same decision; a swing past {@code driftThreshold} (default 2x or half,
 * whichever direction) pauses {@link FraudThresholdTuner} and {@link FraudAbuseHypothesisScanner}'s tenant-wide
 * autonomy to L0 (same pattern as {@code CoverageMonitor}) rather than letting them keep tuning against a baseline
 * that no longer reflects reality — a sale day or a new fraud pattern looks identical to noise without this check.
 */
@Component
public class FraudDriftMonitor {
    private static final Logger log = LoggerFactory.getLogger(FraudDriftMonitor.class);
    private static final List<String> FRAUD_DECISIONS = List.of("payment_risk", "rto_risk", "address_risk", "return_risk");
    private static final List<String> GUARDED_LEARNERS = List.of(FraudThresholdTuner.class.getSimpleName(), FraudAbuseHypothesisScanner.class.getSimpleName());

    private final DecisionRecordRepository decisionRecords;
    private final LearnerRepository learners;

    @Value("${oms.learning.fraud-drift-multiple:2.0}") private double driftMultiple;

    public FraudDriftMonitor(DecisionRecordRepository decisionRecords, LearnerRepository learners) {
        this.decisionRecords = decisionRecords;
        this.learners = learners;
    }

    @Scheduled(fixedDelayString = "${oms.learning.fraud-drift-sweep-ms:86400000}", initialDelayString = "${oms.learning.fraud-drift-sweep-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Fraud drift sweep failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(35))));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::checkTenant);
    }

    private void checkTenant() {
        boolean drifted = false;
        for (String decision : FRAUD_DECISIONS) {
            Double baseline = confirmedRate(decision, Instant.now().minus(Duration.ofDays(35)), Instant.now().minus(Duration.ofDays(7)));
            Double recent = confirmedRate(decision, Instant.now().minus(Duration.ofDays(7)), Instant.now());
            if (baseline == null || recent == null || baseline < 0.01) continue;
            double ratio = recent / baseline;
            if (ratio >= driftMultiple || ratio <= 1.0 / driftMultiple) {
                log.warn("Fraud feature drift on {}: baseline rate {} -> recent rate {} (ratio {})", decision, baseline, recent, ratio);
                drifted = true;
            }
        }
        if (!drifted) return;
        for (String key : GUARDED_LEARNERS) {
            var s = learners.getOrCreate(key);
            if (s.autonomyLevel() != AutonomyLevel.L0_OBSERVE) learners.setAutonomy(key, AutonomyLevel.L0_OBSERVE, "system:fraud-drift-monitor", Instant.now());
        }
    }

    @SuppressWarnings("unchecked")
    private Double confirmedRate(String decision, Instant from, Instant to) {
        List<DecisionRecord> labelled = decisionRecords.labelledBetween(decision, 1, from, to);
        List<DecisionRecord> withLabel = labelled.stream().filter(r -> r.labels() != null && r.labels().get("fraud_outcome") instanceof Map).toList();
        if (withLabel.size() < 20) return null;
        long confirmed = withLabel.stream().filter(r -> Boolean.TRUE.equals(((Map<String, Object>) r.labels().get("fraud_outcome")).get("confirmed"))).count();
        return (double) confirmed / withLabel.size();
    }
}
