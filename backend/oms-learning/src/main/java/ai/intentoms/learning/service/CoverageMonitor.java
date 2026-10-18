package ai.intentoms.learning.service;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OC-07: pauses learning when outcome coverage drops below a threshold (default 80%), the guard against "webhooks
 * stop arriving, outcomes look all good" (requirements doc §11, silent data loss). Coverage per decision = episodes
 * old enough that an outcome should have arrived (created between the window start and now minus the observation
 * lag, default 3 days) that have at least one outcome. Only decisions some learner reads are checked, and a breach
 * pauses only those learners, with the reason recorded; when coverage recovers, learners this monitor paused resume.
 */
@Component
public class CoverageMonitor {
    private static final Logger log = LoggerFactory.getLogger(CoverageMonitor.class);
    static final String ACTOR = "system:coverage-monitor";
    static final String REASON_PREFIX = "outcome coverage";

    private final EpisodeRepository episodes;
    private final LearnerRegistry registry;
    private final LearnerGovernance governance;
    private final DecisionRecordRepository decisionRecords;
    private final Metrics metrics;
    private final ConcurrentHashMap<String, Double> lastCoverage = new ConcurrentHashMap<>();

    @Value("${oms.learning.coverage-threshold:0.8}") private double threshold;
    @Value("${oms.learning.coverage-window-days:14}") private int windowDays;
    @Value("${oms.learning.coverage-lag-days:3}") private int lagDays;
    @Value("${oms.learning.coverage-min-episodes:20}") private int minEpisodes;

    public CoverageMonitor(EpisodeRepository episodes, LearnerRegistry registry, LearnerGovernance governance, DecisionRecordRepository decisionRecords, Metrics metrics) {
        this.episodes = episodes;
        this.registry = registry;
        this.governance = governance;
        this.decisionRecords = decisionRecords;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${oms.learning.coverage-sweep-ms:3600000}", initialDelayString = "${oms.learning.coverage-sweep-ms:3600000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Coverage sweep failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(since));
        for (String tenant : tenants) TenantContext.runAs(tenant, () -> sweepTenant(since));
    }

    /** Latest measured coverage per decision for the current tenant (Learning Console, OC-07 card). */
    public java.util.Map<String, Double> latest() {
        String prefix = TenantContext.require() + "|";
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        lastCoverage.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k.substring(prefix.length()), v); });
        return out;
    }

    private void sweepTenant(Instant since) {
        Instant observedBefore = Instant.now().minus(Duration.ofDays(lagDays));
        // A learner is judged by the worst-covered decision it reads: one healthy decision must not resume a learner
        // another decision's gap paused.
        java.util.Map<String, Double> worst = new java.util.TreeMap<>();
        java.util.Map<String, String> worstDecision = new java.util.HashMap<>();
        for (String decision : registry.outcomeDecisions()) {
            long[] c = episodes.coverage(decision, since, observedBefore);
            if (c[0] < minEpisodes) continue; // too few mature-enough episodes to judge either way
            double coverage = (double) c[1] / c[0];
            lastCoverage.put(TenantContext.require() + "|" + decision, coverage);
            metrics.count("oms.learning.coverage", coverage, "decision", decision);
            if (coverage < threshold) metrics.count("oms.learning.coverage.breach", "decision", decision);
            for (String learner : registry.learnersReadingOutcomesOf(decision)) {
                if (coverage < worst.getOrDefault(learner, Double.MAX_VALUE)) {
                    worst.put(learner, coverage);
                    worstDecision.put(learner, decision);
                }
            }
        }
        worst.forEach((learner, coverage) -> {
            var gate = governance.gate(learner);
            if (coverage < threshold && gate.mayRun()) {
                String reason = String.format(java.util.Locale.ROOT, "%s for %s is %.0f%% (floor %.0f%%)", REASON_PREFIX, worstDecision.get(learner), coverage * 100, threshold * 100);
                log.warn("{}; pausing learner {}", reason, learner);
                governance.pause(learner, reason, ACTOR);
            } else if (coverage >= threshold && !gate.mayRun() && gate.reason().startsWith("paused: " + REASON_PREFIX)) {
                governance.resume(learner, ACTOR);
            }
        });
    }
}
