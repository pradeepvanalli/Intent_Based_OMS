package ai.intentoms.learning.explain;

import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.promotion.ProposalOwnership;
import ai.intentoms.learning.service.CoverageMonitor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NF-09: per-learner metrics: update lag (time since the learner last ran), outcome coverage of the decisions it
 * learns from, proposal yield (proposals drafted in the window), promotion rate (promoted / drafted) and rollback
 * rate (rolled back / promoted). Served by {@code GET /api/v1/learning/metrics} and exported as Micrometer gauges
 * ({@code oms.learning.learner.update_lag_seconds}, {@code .coverage}, {@code .proposal_yield}, {@code .promotion_rate},
 * {@code .rollback_rate}, tagged by tenant and learner), refreshed every minute. Runs and skips are counters written
 * by {@link LearnerGovernance}; facts written by the fact repository.
 *
 * <p>E-3: a proposal counts for the learner that owns its target ({@code ProposalOwnership}), whoever drafted it: its own
 * drafts, and those of the template or LLM author, the demo seed and people. {@code proposalsDraftedByOthers} says how
 * many of the drafted ones did not come from the learner itself.
 */
@Service
public class LearnerMetricsService {
    private static final Logger log = LoggerFactory.getLogger(LearnerMetricsService.class);
    static final int WINDOW_DAYS = 30;

    private final LearnerRegistry registry;
    private final LearnerGovernance governance;
    private final CoverageMonitor coverage;
    private final JdbcClient jdbc;
    private final Metrics metrics;
    private final ai.intentoms.learning.store.ProposalRepository proposals;
    private final ProposalOwnership ownership;
    private final Map<String, AtomicReference<LearnerMetrics>> gauges = new ConcurrentHashMap<>();

    public LearnerMetricsService(LearnerRegistry registry, LearnerGovernance governance, CoverageMonitor coverage, JdbcClient jdbc, Metrics metrics,
                                 ai.intentoms.learning.store.ProposalRepository proposals, ProposalOwnership ownership) {
        this.proposals = proposals;
        this.ownership = ownership;
        this.registry = registry;
        this.governance = governance;
        this.coverage = coverage;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public record LearnerMetrics(String learnerKey, String displayName, Instant lastRunAt, Long updateLagSeconds, Double coverage, long proposalsDrafted,
                                 long proposalsPromoted, long proposalsRolledBack, double proposalYieldPerWeek, double promotionRate, double rollbackRate,
                                 boolean running, String gateReason, long proposalsDraftedByOthers) {}

    public List<LearnerMetrics> current() {
        Map<String, LearnerState> states = new HashMap<>();
        governance.states().forEach(s -> states.put(s.learnerKey(), s));
        Map<String, long[]> proposals = proposalCounts();
        Map<String, Double> cov = coverage.latest();
        Instant now = Instant.now();
        List<LearnerMetrics> out = new ArrayList<>();
        for (LearnerDescriptor d : registry.descriptors()) {
            LearnerState s = states.get(d.key());
            Instant last = s == null ? null : s.lastRunAt();
            Double c = d.outcomeDecisions().stream().map(cov::get).filter(java.util.Objects::nonNull).min(Double::compare).orElse(null);
            long[] p = proposals.getOrDefault(d.key(), new long[4]);
            var gate = governance.gate(d.key());
            out.add(new LearnerMetrics(d.key(), d.displayName(), last, last == null ? null : Duration.between(last, now).toSeconds(), c,
                p[0], p[1], p[2], round(p[0] / (WINDOW_DAYS / 7.0)), p[0] == 0 ? 0 : round((double) p[1] / p[0]), p[1] == 0 ? 0 : round((double) p[2] / p[1]),
                gate.mayRun(), gate.reason(), p[3]));
        }
        return out;
    }

    /** {drafted, promoted, rolledBack, draftedByOthers} per owning learner over the window (E-3). */
    private Map<String, long[]> proposalCounts() {
        Map<String, long[]> out = new HashMap<>();
        for (Proposal p : proposals.createdSince(Instant.now().minus(Duration.ofDays(WINDOW_DAYS)), 10_000)) {
            String author = ProposalOwnership.authorLearner(p.createdBy());
            // A learner's yield counts its own drafts; a draft by a person, the author or the template counts for the
            // learner that owns its target (E-3). D-4 made the owner govern a learner's draft of another's target, not own its yield.
            String key = author != null && ownership.governors(p).contains(author) ? author
                : ownership.owner(p).orElse(author != null ? author : String.valueOf(p.createdBy()));
            long[] c = out.computeIfAbsent(key, k -> new long[4]);
            c[0]++;
            if (p.promotedAt() != null) c[1]++;
            if (p.rolledBackAt() != null) c[2]++;
            if (!key.equals(author)) c[3]++;
        }
        return out;
    }

    @Scheduled(fixedDelayString = "${oms.learning.metrics-refresh-ms:60000}", initialDelayString = "${oms.learning.metrics-refresh-ms:60000}")
    public void refreshGauges() {
        List<String> tenants = jdbc.sql("select tenant_id from tenant").query(String.class).list();
        for (String t : tenants) {
            try { TenantContext.runAs(t, () -> current().forEach(m -> publish(t, m))); }
            catch (RuntimeException e) { log.debug("Learner metrics refresh failed for {}: {}", t, e.toString()); }
        }
    }

    private void publish(String tenant, LearnerMetrics m) {
        String k = tenant + "|" + m.learnerKey();
        AtomicReference<LearnerMetrics> ref = gauges.computeIfAbsent(k, x -> {
            AtomicReference<LearnerMetrics> r = new AtomicReference<>(m);
            register("oms.learning.learner.update_lag_seconds", tenant, m.learnerKey(), r, v -> v.updateLagSeconds() == null ? Double.NaN : v.updateLagSeconds());
            register("oms.learning.learner.coverage", tenant, m.learnerKey(), r, v -> v.coverage() == null ? Double.NaN : v.coverage());
            register("oms.learning.learner.proposal_yield", tenant, m.learnerKey(), r, LearnerMetrics::proposalYieldPerWeek);
            register("oms.learning.learner.promotion_rate", tenant, m.learnerKey(), r, LearnerMetrics::promotionRate);
            register("oms.learning.learner.rollback_rate", tenant, m.learnerKey(), r, LearnerMetrics::rollbackRate);
            return r;
        });
        ref.set(m);
    }

    private void register(String name, String tenant, String learner, AtomicReference<LearnerMetrics> ref, java.util.function.ToDoubleFunction<LearnerMetrics> f) {
        Gauge.builder(name, ref, r -> f.applyAsDouble(r.get())).tag("tenant", tenant).tag("learner", learner).register(metrics.registry());
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }
}
