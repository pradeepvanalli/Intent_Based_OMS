package ai.intentoms.learning.service;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.LearnerRepository;
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

/**
 * OC-07: a data-quality monitor that pauses learning automatically when outcome coverage drops below a threshold
 * (default 80%) — the failure mode this guards is "webhooks stop arriving, outcomes look all good" (requirements
 * doc §11, Silent data loss): a learner updating on an ever-shrinking, biased sample of episodes is worse than one
 * that's paused and honest about it. Scope: per-tenant, every enabled learner is paused together when any tracked
 * decision's coverage breaches the floor, since this build doesn't yet know which decisions feed which learner one
 * for one — safe to narrow to per-learner scope once that mapping exists.
 */
@Component
public class CoverageMonitor {
    private static final Logger log = LoggerFactory.getLogger(CoverageMonitor.class);

    private final EpisodeRepository episodes;
    private final LearnerRepository learners;
    private final DecisionRecordRepository decisionRecords;
    private final Metrics metrics;

    @Value("${oms.learning.coverage-threshold:0.8}") private double threshold;
    @Value("${oms.learning.coverage-window-days:7}") private int windowDays;

    public CoverageMonitor(EpisodeRepository episodes, LearnerRepository learners, DecisionRecordRepository decisionRecords, Metrics metrics) {
        this.episodes = episodes;
        this.learners = learners;
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

    private void sweepTenant(Instant since) {
        List<String> decisions = decisionRecords.decisionsWithRecords(since);
        boolean breach = false;
        for (String decision : decisions) {
            long total = episodes.countSince(decision, since);
            if (total < 10) continue; // too few episodes yet to judge coverage either way
            long withOutcome = episodes.forDecisionSince(decision, since, 10_000).stream()
                .filter(e -> e.status() != ai.intentoms.learning.model.EpisodeStatus.OPEN).count();
            double coverage = (double) withOutcome / total;
            metrics.count("oms.learning.coverage", coverage, "decision", decision);
            if (coverage < threshold) {
                log.warn("Outcome coverage for decision {} is {} (threshold {}); pausing learners for this tenant", decision, coverage, threshold);
                breach = true;
            }
        }
        if (!breach) return;
        metrics.count("oms.learning.coverage.breach", "tenant", TenantContext.require());
        for (LearnerState s : learners.all()) {
            if (s.enabled() && s.autonomyLevel() != AutonomyLevel.L0_OBSERVE) {
                learners.setAutonomy(s.learnerKey(), AutonomyLevel.L0_OBSERVE, "system:coverage-monitor", Instant.now());
                metrics.count("oms.learning.learner.auto_paused", "learner", s.learnerKey(), "reason", "coverage");
            }
        }
    }
}
