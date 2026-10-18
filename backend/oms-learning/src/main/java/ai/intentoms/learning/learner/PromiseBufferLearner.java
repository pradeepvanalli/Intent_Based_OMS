package ai.intentoms.learning.learner;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
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
import java.util.Map;

/**
 * CX-02: the promise buffer that maximises conversion x on-time per segment. A longer buffer makes the promise easier
 * to keep but loses shoppers; a shorter one converts better and misses more. From storefront impressions
 * ({@code POST /api/v1/promise/conversion}) joined to delivery outcomes, each buffer's conversion rate and on-time rate
 * are smoothed (Beta(1,1)), multiplied, and the best buffer with enough impressions becomes {@code PROMISE_BUFFER_DAYS}
 * for the segment ({@code channel:<channel>}) and for all traffic ({@code default}). Promises use it through
 * {@code ParameterResolver} ({@code promise.buffer_days}) once promoted for live use.
 */
@Component
public class PromiseBufferLearner implements GovernedLearner {
    public static final String KEY = "promise_buffer";
    public static final String PARAMETER = "promise.buffer_days";
    private static final Logger log = LoggerFactory.getLogger(PromiseBufferLearner.class);

    private final PromiseConversionRepository conversions;
    private final FulfilmentFacts facts;
    private final DecisionRecordRepository decisionRecords;
    private final LearnerGovernance governance;

    @Value("${oms.learning.cx02.window-days:90}") private int windowDays = 90;
    @Value("${oms.learning.cx02.min-impressions:20}") private int minImpressions = 20;

    public PromiseBufferLearner(PromiseConversionRepository conversions, FulfilmentFacts facts, DecisionRecordRepository decisionRecords, LearnerGovernance governance) {
        this.conversions = conversions;
        this.facts = facts;
        this.decisionRecords = decisionRecords;
        this.governance = governance;
    }

    public static String segment(String channel) { return channel == null || channel.isBlank() ? "default" : "channel:" + channel; }

    @Scheduled(fixedDelayString = "${oms.learning.cx02.sweep-ms:86400000}", initialDelayString = "${oms.learning.cx02.sweep-ms:950000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Promise buffer sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        List<PromiseConversionRepository.BufferStats> rows = conversions.stats(Instant.now().minus(Duration.ofDays(windowDays)));
        Map<String, List<PromiseConversionRepository.BufferStats>> bySegment = new LinkedHashMap<>();
        Map<Integer, long[]> pooled = new LinkedHashMap<>();
        for (var r : rows) {
            bySegment.computeIfAbsent(r.segment(), k -> new ArrayList<>()).add(r);
            long[] p = pooled.computeIfAbsent(r.bufferDays(), k -> new long[4]);
            p[0] += r.impressions(); p[1] += r.conversions(); p[2] += r.delivered(); p[3] += r.onTime();
        }
        List<PromiseConversionRepository.BufferStats> all = new ArrayList<>();
        pooled.forEach((b, p) -> all.add(new PromiseConversionRepository.BufferStats("default", b, p[0], p[1], p[2], p[3])));
        bySegment.put("default", all);
        int written = 0;
        for (var e : bySegment.entrySet()) {
            Best best = best(e.getValue(), minImpressions);
            if (best == null) continue;
            if (facts.number(SubjectType.SEGMENT, e.getKey(), FactKey.PROMISE_BUFFER_DAYS, best.bufferDays(), (int) best.impressions(), KEY, 0.5)) written++;
        }
        return written;
    }

    record Best(int bufferDays, double objective, long impressions) {}

    /** The buffer with the highest smoothed conversion x on-time among buffers with enough impressions; null when none. */
    static Best best(List<PromiseConversionRepository.BufferStats> rows, int minImpressions) {
        Best best = null;
        for (var r : rows) {
            if (r.impressions() < minImpressions) continue;
            double conversion = (r.conversions() + 1.0) / (r.impressions() + 2.0);
            double onTime = (r.onTime() + 1.0) / (r.delivered() + 2.0);
            double objective = conversion * onTime;
            if (best == null || objective > best.objective() + 1e-12) best = new Best(r.bufferDays(), objective, r.impressions());
        }
        return best;
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Promise buffer", "Finds how many days of padding on the delivery promise win the most shoppers while still being kept.",
            LearnerDescriptor.Loop.FAST, "CX-02").feeds(PARAMETER);
    }
}
