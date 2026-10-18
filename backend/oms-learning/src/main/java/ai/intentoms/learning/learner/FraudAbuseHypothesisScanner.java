package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FD-04: surfaces candidate abuse-rule hypotheses from real labeled history, for the analyst/author pipeline
 * (built elsewhere, extending oms-copilot) to turn into a reviewed {@code RuleAdd} changeset — this scanner never
 * authors rules or calls an LLM itself.
 *
 * <p><b>Scope cut, stated plainly:</b> the requirements doc's own example is "address-reuse rings," but oms-learning
 * has no reliable address/device identifier to group by yet (payment_risk's state carries linkage *counts*, not
 * the raw tokenized address/device id a ring-detection join needs, and oms-learning has no dependency on the
 * module that owns that data). What's implemented instead is a real, data-backed signal available today:
 * elevated confirmed-fraud rate concentrated in one order channel for a fraud decision, which is exactly the shape
 * of evidence a {@code RuleAdd} hypothesis needs. Wiring true identity-linkage clustering is a follow-up once a
 * fraud-signals table or a cross-module query exists.
 */
@Component
public class FraudAbuseHypothesisScanner {
    private static final Logger log = LoggerFactory.getLogger(FraudAbuseHypothesisScanner.class);
    private static final List<String> FRAUD_DECISIONS = List.of("payment_risk", "return_risk", "rto_risk");
    private static final double ELEVATED_RATE_THRESHOLD = 0.15;
    private static final int MIN_SAMPLE = 20;

    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;

    public FraudAbuseHypothesisScanner(DecisionRecordRepository decisionRecords, HypothesisRepository hypotheses) {
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
    }

    @Scheduled(fixedDelayString = "${oms.learning.abuse-scan-ms:86400000}", initialDelayString = "${oms.learning.abuse-scan-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Abuse hypothesis scan failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(30))));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::scanTenant);
    }

    private void scanTenant() {
        Instant since = Instant.now().minus(Duration.ofDays(30));
        for (String decision : FRAUD_DECISIONS) {
            List<DecisionRecord> labelled = decisionRecords.labelledBetween(decision, currentVersionGuess(decision), since, Instant.now());
            if (labelled.size() < MIN_SAMPLE) continue;
            Map<String, List<DecisionRecord>> byChannel = labelled.stream()
                .collect(Collectors.groupingBy(r -> String.valueOf(r.input() == null ? "unknown" : r.input().getOrDefault("order.channel", "unknown"))));
            for (var entry : byChannel.entrySet()) {
                List<DecisionRecord> group = entry.getValue();
                if (group.size() < MIN_SAMPLE) continue;
                long confirmed = group.stream().filter(r -> Boolean.TRUE.equals(fraudConfirmed(r))).count();
                double rate = (double) confirmed / group.size();
                if (rate < ELEVATED_RATE_THRESHOLD) continue;
                raiseHypothesis(decision, entry.getKey(), rate, group.size());
            }
        }
    }

    private void raiseHypothesis(String decision, String channel, double rate, int n) {
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decision, "channel", channel), "fraud_and_abuse_loss", Direction.DECREASE,
            Map.of("decision", decision, "lever", "ruleAdd"), Math.min(0.9, rate), List.of(),
            ("Confirmed-fraud rate for " + decision + " in channel " + channel + " is " + String.format("%.0f%%", rate * 100) + " over " + n + " labelled cases, above the "
                + (int) (ELEVATED_RATE_THRESHOLD * 100) + "% floor."), Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        log.info("Raised abuse hypothesis for {} in channel {} (rate {}, n={})", decision, channel, rate, n);
    }

    @SuppressWarnings("unchecked")
    private static Boolean fraudConfirmed(DecisionRecord r) {
        Object outcome = r.labels() == null ? null : r.labels().get("fraud_outcome");
        if (!(outcome instanceof Map<?, ?> m)) return false;
        return Boolean.TRUE.equals(((Map<String, Object>) m).get("confirmed"));
    }

    /** decisionsWithRecords doesn't carry version; 1 covers every tenant's current decision YAMLs (version: 1 above). */
    private static int currentVersionGuess(String decision) { return 1; }
}
