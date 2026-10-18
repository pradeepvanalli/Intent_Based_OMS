package ai.intentoms.inventory.learning;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.tenant.TenantContext;
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
 * IU-05: how much of a supplier's announced inbound actually arrives on time. Each ASN receipt over the last 180 days
 * scores {@code min(1, received / expected)}, halved when it arrived more than a day late; a supplier's reliability is
 * the mean score shrunk toward a 0.9 prior with weight 5 receipts, written as {@code INBOUND_RELIABILITY} (subject
 * {@code SUPPLIER}). ATP counts that share of the supplier's inbound once {@code inventory.inbound_reliability} is live.
 */
@Component
public class InboundReliabilityLearner implements GovernedLearner {
    public static final String KEY = "inventory_inbound_reliability";
    private static final Logger log = LoggerFactory.getLogger(InboundReliabilityLearner.class);
    static final double PRIOR = 0.9;
    static final double PRIOR_WEIGHT = 5;

    private final InventoryLearningRepository repo;
    private final InventoryLearnerSupport support;
    private final LearnerGovernance governance;

    public InboundReliabilityLearner(InventoryLearningRepository repo, InventoryLearnerSupport support, LearnerGovernance governance) {
        this.repo = repo;
        this.support = support;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${oms.inventory.learning.inbound-sweep-ms:86400000}", initialDelayString = "${oms.inventory.learning.inbound-initial-ms:1500000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Inbound reliability learner failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        for (String tenant : support.tenants()) {
            try { TenantContext.runAs(tenant, this::learnForTenant); }
            catch (RuntimeException e) { log.warn("Inbound reliability learner failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    /** Reliability per supplier written this run. */
    public Map<String, Double> learnForTenant() {
        if (!governance.begin(KEY).mayRun()) return Map.of();
        governance.recordRun(KEY);
        Map<String, List<Double>> scores = new LinkedHashMap<>();
        for (InventoryLearningRepository.Receipt r : repo.receipts(Instant.now().minus(Duration.ofDays(180)))) {
            if (r.expectedQty() <= 0) continue;
            scores.computeIfAbsent(r.supplierId(), k -> new ArrayList<>()).add(score(r));
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : scores.entrySet()) {
            double reliability = shrink(e.getValue());
            out.put(e.getKey(), reliability);
            support.writeNumber(SubjectType.SUPPLIER, e.getKey(), FactKey.INBOUND_RELIABILITY, reliability, Math.min(1.0, e.getValue().size() / 30.0),
                e.getValue().size(), KEY, 0.005);
        }
        return out;
    }

    static double score(InventoryLearningRepository.Receipt r) {
        double fill = Math.min(1.0, (double) r.receivedQty() / r.expectedQty());
        boolean late = r.receivedAt().isAfter(r.expectedAt().plus(Duration.ofDays(1)));
        return late ? fill * 0.5 : fill;
    }

    static double shrink(List<Double> scores) {
        double sum = 0;
        for (double s : scores) sum += s;
        return (sum + PRIOR * PRIOR_WEIGHT) / (scores.size() + PRIOR_WEIGHT);
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Supplier reliability", "Learns how much of each supplier's announced stock really arrives on time, so promises don't count on stock that won't come.",
            LearnerDescriptor.Loop.FAST, "IU-05").feeds(LearnedInventoryParameters.INBOUND_RELIABILITY);
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
