package ai.intentoms.learning.promotion;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.experiment.ExperimentMetricsRepository;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EX-05: watches every change promoted in the last {@code oms.learning.guardrail-watch-days} (default 7) and rolls it
 * back automatically ({@link PromotionService#rollback}) when a guardrail gets worse than it was before the change:
 * <ul>
 *   <li>override rate above 5% and at least 2 points above before;</li>
 *   <li>p95 decision latency above 300 ms and 25% above before;</li>
 *   <li>customer-contact rate (episodes with a contact term) at least 2 points above before;</li>
 *   <li>fraud false-positive rate (flagged orders later confirmed good) at least 2 points above before.</li>
 * </ul>
 * A decision change is measured on the version it published against the version it replaced; a config or parameter
 * change on the decisions its learner reads, after versus before the promotion. Each side needs at least
 * {@code oms.learning.guardrail-min-sample} (default 30) decisions. Rows written by the demo seeder are excluded.
 */
@Component
public class GuardrailBreachMonitor {
    private static final Logger log = LoggerFactory.getLogger(GuardrailBreachMonitor.class);
    static final double MAX_OVERRIDE_RATE = 0.05;
    static final double POINTS = 0.02;
    static final double MAX_P95_MS = 300;

    private final ProposalRepository proposals;
    private final DecisionRecordRepository decisionRecords;
    private final ExperimentMetricsRepository metrics;
    private final PromotionService promotion;
    private final LearnerRegistry registry;
    private final Metrics counters;
    private final ai.intentoms.learning.experiment.ExperimentManager experiments;

    @Value("${oms.learning.guardrail-watch-days:7}") private int watchDays;
    @Value("${oms.learning.guardrail-min-sample:30}") private int minSample;

    public GuardrailBreachMonitor(ProposalRepository proposals, DecisionRecordRepository decisionRecords, ExperimentMetricsRepository metrics,
                                  PromotionService promotion, LearnerRegistry registry, Metrics counters, ai.intentoms.learning.experiment.ExperimentManager experiments) {
        this.proposals = proposals;
        this.decisionRecords = decisionRecords;
        this.metrics = metrics;
        this.promotion = promotion;
        this.registry = registry;
        this.counters = counters;
        this.experiments = experiments;
    }

    @Scheduled(fixedDelayString = "${oms.learning.guardrail-sweep-ms:1800000}", initialDelayString = "${oms.learning.guardrail-sweep-ms:1800000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Guardrail breach sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(watchDays + 1))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        Instant now = Instant.now();
        int rolledBack = 0;
        for (Proposal p : proposals.promotedSince(now.minus(Duration.ofDays(watchDays)), 200)) {
            try {
                String breach = breach(p, now);
                if (breach == null) continue;
                promotion.rollback(p.proposalId(), Actor.system("guardrail-breach-monitor"), "guardrail breach: " + breach);
                counters.count("oms.learning.guardrail.rollback", "target", p.changeset().target().type());
                log.warn("Rolled back proposal {} on {}: {}", p.proposalId(), p.changeset().target(), breach);
                rolledBack++;
            } catch (RuntimeException e) {
                log.warn("Guardrail check or rollback of {} failed: {}", p.proposalId(), e.toString());
            }
        }
        // EX-05 "during rollout": a canary whose candidate version breaks a guardrail against the live version, over the
        // same period, is stopped and its change rolled back before it ever goes live.
        for (var e : experiments.running()) {
            if (e.mode() != ai.intentoms.learning.model.ExperimentMode.CANARY || e.decisionName() == null || e.candidateVersion() == null) continue;
            try {
                ExperimentMetricsRepository.Guardrails candidate = metrics.guardrails(e.decisionName(), e.candidateVersion(), e.startAt(), now);
                ExperimentMetricsRepository.Guardrails live = metrics.guardrails(e.decisionName(), e.baselineVersion(), e.startAt(), now);
                String breach = compare(e.decisionName(), live, candidate, minSample);
                if (breach == null) continue;
                Proposal p = proposals.get(e.proposalId());
                promotion.rollbackCanary(p, Actor.system("guardrail-breach-monitor"), "guardrail breach during the trial: " + breach);
                counters.count("oms.learning.guardrail.rollback", "target", "canary");
                log.warn("Stopped canary {} for proposal {}: {}", e.experimentId(), e.proposalId(), breach);
                rolledBack++;
            } catch (RuntimeException ex) {
                log.warn("Guardrail check of canary {} failed: {}", e.experimentId(), ex.toString());
            }
        }
        return rolledBack;
    }

    /** The first guardrail this promotion broke, or null. */
    String breach(Proposal p, Instant now) {
        Instant promotedAt = p.promotedAt();
        Duration window = Duration.between(promotedAt, now);
        if (window.isNegative() || window.isZero()) return null;
        Instant beforeFrom = promotedAt.minus(window.compareTo(Duration.ofDays(1)) < 0 ? Duration.ofDays(1) : window);
        Map<String, Object> snapshot = proposals.rollbackSnapshot(p.proposalId()).orElse(Map.of());
        for (Comparison c : comparisons(p, snapshot)) {
            ExperimentMetricsRepository.Guardrails after = metrics.guardrails(c.decision(), c.afterVersion(), promotedAt, now);
            ExperimentMetricsRepository.Guardrails before = metrics.guardrails(c.decision(), c.beforeVersion(), beforeFrom, promotedAt);
            String b = compare(c.decision(), before, after, minSample);
            if (b != null) return b;
        }
        return null;
    }

    record Comparison(String decision, Integer beforeVersion, Integer afterVersion) {}

    private List<Comparison> comparisons(Proposal p, Map<String, Object> snapshot) {
        List<Comparison> out = new ArrayList<>();
        if ("decision".equals(p.changeset().target().type())) {
            Integer before = snapshot.get("previousVersion") instanceof Number n ? n.intValue() : null;
            Integer after = snapshot.get("newVersion") instanceof Number n ? n.intValue() : null;
            out.add(new Comparison(p.changeset().target().name(), before, after));
        } else {
            String learner = PromotionService.learnerKeyOf(p);
            registry.descriptor(learner).ifPresent(d -> d.outcomeDecisions().forEach(dec -> out.add(new Comparison(dec, null, null))));
        }
        return out;
    }

    /** Pure comparison of one decision's guardrails before and after a change. */
    static String compare(String decision, ExperimentMetricsRepository.Guardrails before, ExperimentMetricsRepository.Guardrails after, int minSample) {
        if (after.decisions() < minSample || before.decisions() < minSample) return null;
        if (after.overrideRate() > MAX_OVERRIDE_RATE && after.overrideRate() >= before.overrideRate() + POINTS) {
            return String.format(Locale.ROOT, "%s override rate %.1f%% (was %.1f%%)", decision, after.overrideRate() * 100, before.overrideRate() * 100);
        }
        if (after.latencyP95() > MAX_P95_MS && after.latencyP95() > before.latencyP95() * 1.25) {
            return String.format(Locale.ROOT, "%s p95 latency %.0f ms (was %.0f ms)", decision, after.latencyP95(), before.latencyP95());
        }
        if (after.episodes() >= minSample && before.episodes() >= minSample && after.contactRate() >= before.contactRate() + POINTS) {
            return String.format(Locale.ROOT, "%s customer contact rate %.1f%% (was %.1f%%)", decision, after.contactRate() * 100, before.contactRate() * 100);
        }
        if (after.fraudLabelled() >= minSample && before.fraudLabelled() >= minSample && after.falsePositiveRate() >= before.falsePositiveRate() + POINTS) {
            return String.format(Locale.ROOT, "%s fraud false-positive rate %.1f%% (was %.1f%%)", decision, after.falsePositiveRate() * 100, before.falsePositiveRate() * 100);
        }
        return null;
    }
}
