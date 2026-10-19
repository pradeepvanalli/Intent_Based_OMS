package ai.intentoms.learning.service;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requirements §7: every reward term is normalised to [0,1] against the tenant's own baseline, so a weight means the
 * same thing in every tenant. Rate terms (fill, on time, aged stock) already are. For the penalty terms (cost ratio,
 * splits, fraud loss, contacts) this job takes the 95th percentile of each term's raw value as its scale (floor 0.05),
 * stored in tenant config {@code learning.reward_baseline}; rewards then use {@code min(1, raw / scale)} and keep the raw
 * value alongside. With no baseline yet, terms stay as computed.
 *
 * <p>Which episodes set the scale: those of customers in the persistent holdout when the term has at least
 * {@value #MIN_SAMPLES} of them in the last 90 days. The holdout runs on the baseline policy, so its outcomes are the
 * tenant's own baseline, not moved by what learning changed; scaling on episodes learning already shaped would let the
 * yardstick drift with the policy it measures. Otherwise (no holdout yet, or too few holdout episodes) all recent
 * episodes are used. The stored baseline records which, per term ({@code sources}) and overall ({@code source}:
 * holdout, recent, mixed or none).
 */
@Component
public class RewardBaselineService {
    private static final Logger log = LoggerFactory.getLogger(RewardBaselineService.class);
    public static final String KEY = "learning.reward_baseline";
    public static final List<String> SCALED_TERMS = List.of("cost", "split", "fraud", "cx");
    static final double FLOOR = 0.05;
    static final int MIN_SAMPLES = 30;

    private final JdbcClient jdbc;
    private final TenantConfigStore config;
    private final DecisionRecordRepository decisionRecords;

    public RewardBaselineService(JdbcClient jdbc, TenantConfigStore config, DecisionRecordRepository decisionRecords) {
        this.jdbc = jdbc;
        this.config = config;
        this.decisionRecords = decisionRecords;
    }

    /** Scale per penalty term for the current tenant; empty until enough episodes exist. */
    public Map<String, Double> current() {
        Map<String, Object> raw = config.getMap(KEY);
        Map<String, Double> out = new LinkedHashMap<>();
        Object scales = raw.get("scales");
        if (scales instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v instanceof Number n && n.doubleValue() > 0) out.put(String.valueOf(k), n.doubleValue()); });
        return out;
    }

    @Scheduled(cron = "${oms.learning.reward-baseline-cron:0 30 6 * * MON}", zone = "UTC")
    public void scheduled() {
        try {
            for (String t : decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(90)))) TenantContext.runAs(t, this::recompute);
        } catch (RuntimeException e) { log.error("Reward baseline recompute failed: {}", e.toString()); }
    }

    /**
     * Recomputes the current tenant's scales from the last 90 days of rewarded episodes, the holdout's when there are
     * enough of them for a term (see the class comment). Returns what was stored.
     */
    public Map<String, Object> recompute() {
        Map<String, Double> scales = new LinkedHashMap<>();
        Map<String, Long> samples = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofDays(90)));
        for (String term : SCALED_TERMS) {
            Object[] held = p95(term, true, since);
            Object[] recent = (long) held[0] >= MIN_SAMPLES ? held : p95(term, false, since);
            String source = recent == held ? "holdout" : "recent";
            long n = (long) recent[0];
            samples.put(term, n);
            if (n >= MIN_SAMPLES && recent[1] instanceof Number p) {
                scales.put(term, Math.max(FLOOR, Math.min(1.0, p.doubleValue())));
                sources.put(term, source);
            }
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("scales", scales);
        stored.put("samples", samples);
        stored.put("sources", sources);
        stored.put("source", sources.isEmpty() ? "none" : sources.values().stream().distinct().count() > 1 ? "mixed" : sources.values().iterator().next());
        stored.put("holdoutEpisodes", holdoutEpisodes(since));
        stored.put("computedAt", Instant.now().toString());
        config.set(KEY, stored);
        return stored;
    }

    /** [count of positive values, their 95th percentile] for one term, over holdout episodes only or over all of them. */
    private Object[] p95(String term, boolean holdoutOnly, Timestamp since) {
        var row = jdbc.sql("""
            select count(*) as n, percentile_cont(0.95) within group (order by v) as p95
            from (select coalesce((e.reward_components->>:raw)::double precision, (e.reward_components->>:term)::double precision) as v
                  from episode e left join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
                  where e.tenant_id = :t and e.reward is not null and e.created_at >= :since
                    and (cast(:held as boolean) = false or d.details->'experiment'->>'arm' = 'holdout')) x
            where v > 0
            """).param("raw", term + "_raw").param("term", term).param("t", TenantContext.require()).param("since", since).param("held", holdoutOnly)
            .query().singleRow();
        return new Object[]{((Number) row.get("n")).longValue(), row.get("p95")};
    }

    private long holdoutEpisodes(Timestamp since) {
        return jdbc.sql("""
            select count(*) from episode e join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
            where e.tenant_id = :t and e.reward is not null and e.created_at >= :since and d.details->'experiment'->>'arm' = 'holdout'
            """).param("t", TenantContext.require()).param("since", since).query(Long.class).single();
    }
}
