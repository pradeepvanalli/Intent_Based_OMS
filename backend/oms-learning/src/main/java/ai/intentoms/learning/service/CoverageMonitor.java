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
 *
 * <p>Coverage is also judged per outcome type, so losing one feed (carrier webhooks, say) is caught even while other
 * outcomes keep every episode "covered". The types a decision is expected to get are configured
 * ({@code learning.coverage.expected_outcomes.<decision>}, a list such as {@code [FulfillmentOutcome]}) or learned from
 * the window before: types that reached at least {@code oms.learning.coverage-type-expected} (default 50%) of its
 * episodes then. A type's coverage is its share now against its share then (or against all episodes when configured);
 * below the threshold it pauses the decision's learners like a gap in overall coverage, and an operator alert is raised.
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
    private final ConcurrentHashMap<String, Double> lastTypeCoverage = new ConcurrentHashMap<>();
    private final OutcomeCoverageRepository byType;
    private final ai.intentoms.platform.config.TenantConfigStore config;
    private final org.springframework.beans.factory.ObjectProvider<ai.intentoms.learning.learner.OperatorAlerts> alerts;
    @Value("${oms.learning.coverage-type-expected:0.5}") private double typeExpected = 0.5;

    @Value("${oms.learning.coverage-threshold:0.8}") private double threshold;
    @Value("${oms.learning.coverage-window-days:14}") private int windowDays;
    @Value("${oms.learning.coverage-lag-days:3}") private int lagDays;
    @Value("${oms.learning.coverage-min-episodes:20}") private int minEpisodes;

    public CoverageMonitor(EpisodeRepository episodes, LearnerRegistry registry, LearnerGovernance governance, DecisionRecordRepository decisionRecords, Metrics metrics,
                           OutcomeCoverageRepository byType, ai.intentoms.platform.config.TenantConfigStore config,
                           org.springframework.beans.factory.ObjectProvider<ai.intentoms.learning.learner.OperatorAlerts> alerts) {
        this.byType = byType;
        this.config = config;
        this.alerts = alerts;
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

    /** Latest coverage per decision and expected outcome type ({@code <decision>|<type>}) for the current tenant. */
    public java.util.Map<String, Double> latestByType() {
        String prefix = TenantContext.require() + "|";
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        lastTypeCoverage.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k.substring(prefix.length()), v); });
        return out;
    }

    /**
     * Coverage of each outcome type the decision is expected to get: the configured types against all episodes, else the
     * types that reached {@code typeExpected} of the episodes in the previous window, against their share then (capped at 1).
     */
    java.util.Map<String, Double> typeCoverage(String decision, Instant since, Instant observedBefore) {
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        OutcomeCoverageRepository.ByType now = byType.byType(decision, since, observedBefore);
        if (now.eligible() < minEpisodes) return out;
        List<Object> configured = config.getList("learning.coverage.expected_outcomes." + decision);
        if (!configured.isEmpty()) {
            for (Object type : configured) out.put(String.valueOf(type), now.share(String.valueOf(type)));
            return out;
        }
        Instant baselineFrom = since.minus(Duration.ofDays(windowDays));
        OutcomeCoverageRepository.ByType before = byType.byType(decision, baselineFrom, since);
        if (before.eligible() < minEpisodes) return out;
        for (String type : before.covered().keySet()) {
            double was = before.share(type);
            if (was >= typeExpected) out.put(type, Math.min(1.0, now.share(type) / was));
        }
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
            String label = decision;
            for (var t : typeCoverage(decision, since, observedBefore).entrySet()) {
                lastTypeCoverage.put(TenantContext.require() + "|" + decision + "|" + t.getKey(), t.getValue());
                if (t.getValue() < coverage) { coverage = t.getValue(); label = decision + " (" + t.getKey() + ")"; }
            }
            for (String learner : registry.learnersReadingOutcomesOf(decision)) {
                if (coverage < worst.getOrDefault(learner, Double.MAX_VALUE)) {
                    worst.put(learner, coverage);
                    worstDecision.put(learner, label);
                }
            }
        }
        java.util.Set<String> alerted = new java.util.HashSet<>();
        worst.forEach((learner, coverage) -> {
            var gate = governance.gate(learner);
            if (coverage < threshold && gate.mayRun()) {
                String reason = String.format(java.util.Locale.ROOT, "%s for %s is %.0f%% (floor %.0f%%)", REASON_PREFIX, worstDecision.get(learner), coverage * 100, threshold * 100);
                log.warn("{}; pausing learner {}", reason, learner);
                governance.pause(learner, reason, ACTOR);
                if (alerted.add(worstDecision.get(learner))) alert(worstDecision.get(learner), reason);
            } else if (coverage >= threshold && !gate.mayRun() && gate.reason().startsWith("paused: " + REASON_PREFIX)) {
                governance.resume(learner, ACTOR);
            }
        });
    }

    private void alert(String what, String reason) {
        var sink = alerts.getIfAvailable();
        if (sink == null) return;
        try {
            sink.raise("learning:coverage:" + TenantContext.require() + ":" + what, "high", "Learning paused: outcomes missing for " + what,
                reason + ". Outcomes may have stopped arriving (a webhook or feed down); learners reading them are paused until coverage recovers.", java.util.Map.of());
        } catch (RuntimeException e) {
            log.warn("Coverage alert failed: {}", e.toString());
        }
    }
}
