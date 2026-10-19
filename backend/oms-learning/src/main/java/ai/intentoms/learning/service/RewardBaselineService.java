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
 * splits, fraud loss, contacts) this job takes the 95th percentile of each term's raw value over the tenant's recent
 * episodes as its scale (floor 0.05), stored in tenant config {@code learning.reward_baseline}; rewards then use
 * {@code min(1, raw / scale)} and keep the raw value alongside. With no baseline yet, terms stay as computed.
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

    /** Recomputes the current tenant's scales from the last 90 days of rewarded episodes. Returns what was stored. */
    public Map<String, Object> recompute() {
        Map<String, Double> scales = new LinkedHashMap<>();
        Map<String, Long> samples = new LinkedHashMap<>();
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofDays(90)));
        for (String term : SCALED_TERMS) {
            var row = jdbc.sql("""
                select count(*) as n,
                       percentile_cont(0.95) within group (order by coalesce((reward_components->>:raw)::double precision, (reward_components->>:term)::double precision)) as p95
                from episode
                where tenant_id = :t and reward is not null and created_at >= :since
                  and coalesce((reward_components->>:raw)::double precision, (reward_components->>:term)::double precision) > 0
                """).param("raw", term + "_raw").param("term", term).param("t", TenantContext.require()).param("since", since).query().singleRow();
            long n = ((Number) row.get("n")).longValue();
            samples.put(term, n);
            if (n >= MIN_SAMPLES && row.get("p95") instanceof Number p) scales.put(term, Math.max(FLOOR, Math.min(1.0, p.doubleValue())));
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("scales", scales);
        stored.put("samples", samples);
        stored.put("computedAt", Instant.now().toString());
        config.set(KEY, stored);
        return stored;
    }
}
