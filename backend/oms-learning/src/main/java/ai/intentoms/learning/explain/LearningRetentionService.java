package ai.intentoms.learning.explain;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NF-10: decision logs and episodes are kept 400 days by default, so a year-on-year peak can be replayed. Daily at
 * 03:30 UTC this removes, per tenant, what is older than the retention window: matured episodes and their outcomes,
 * superseded learned-fact versions and old digests. Decision records are only removed when
 * {@code oms.learning.retention.include-decision-records} is true (they also back the audit trail, which the platform's
 * {@code retention.years} governs). The event ledger is append-only and never pruned. Window: tenant config
 * {@code learning.retention.days}, else {@code oms.learning.retention.days} (default 400, never below 30). A dry run
 * ({@code oms.learning.retention.dry-run}) counts what would go and deletes nothing. Every run is logged and recorded
 * as {@code learning.retention_run}.
 */
@Service
public class LearningRetentionService {
    private static final Logger log = LoggerFactory.getLogger(LearningRetentionService.class);
    public static final String TENANT_DAYS_KEY = "learning.retention.days";
    static final int MIN_DAYS = 30;

    private final JdbcClient jdbc;
    private final TenantConfigStore config;
    private final LearningEvents events;
    private final int defaultDays;
    private final boolean defaultDryRun;
    private final boolean includeDecisionRecords;
    private final boolean enabled;

    public LearningRetentionService(JdbcClient jdbc, TenantConfigStore config, LearningEvents events,
                                    @Value("${oms.learning.retention.days:400}") int defaultDays,
                                    @Value("${oms.learning.retention.dry-run:false}") boolean defaultDryRun,
                                    @Value("${oms.learning.retention.include-decision-records:false}") boolean includeDecisionRecords,
                                    @Value("${oms.learning.retention.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.config = config;
        this.events = events;
        this.defaultDays = defaultDays;
        this.defaultDryRun = defaultDryRun;
        this.includeDecisionRecords = includeDecisionRecords;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${oms.learning.retention.cron:0 30 3 * * *}", zone = "UTC")
    public void scheduled() {
        if (!enabled) return;
        for (String t : jdbc.sql("select tenant_id from tenant order by tenant_id").query(String.class).list()) {
            try { TenantContext.runAs(t, () -> run(defaultDryRun, "system:learning-retention")); }
            catch (RuntimeException e) { log.warn("Learning retention failed for tenant {}: {}", t, e.toString()); }
        }
    }

    public int retentionDays() { return Math.max(MIN_DAYS, config.getInt(TENANT_DAYS_KEY, defaultDays)); }

    @Transactional
    public Map<String, Object> run(boolean dryRun, String by) {
        String t = TenantContext.require();
        int days = retentionDays();
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retentionDays", days);
        result.put("cutoff", cutoff.toInstant().toString());
        result.put("dryRun", dryRun);
        String oldEpisodes = "select episode_id from episode where tenant_id = :t and status = 'MATURE' and created_at < :c";
        result.put("outcomes", apply(dryRun, "outcome", "tenant_id = :t and episode_id in (" + oldEpisodes + ")", t, cutoff));
        result.put("episodes", apply(dryRun, "episode", "tenant_id = :t and status = 'MATURE' and created_at < :c", t, cutoff));
        result.put("supersededFacts", apply(dryRun, "learned_fact", "tenant_id = :t and valid_to is not null and valid_to < :c", t, cutoff));
        result.put("digests", apply(dryRun, "learning_digest", "tenant_id = :t and created_at < :c", t, cutoff));
        if (includeDecisionRecords) result.put("decisionRecords", apply(dryRun, "decision_record", "tenant_id = :t and created_at < :c", t, cutoff));
        log.info("NF-10 learning retention for tenant {} ({} days, {}): {}", t, days, dryRun ? "dry run" : "applied", result);
        events.retentionRun(Actor.parse(by), result);
        return result;
    }

    private long apply(boolean dryRun, String table, String where, String tenant, Timestamp cutoff) {
        if (!List.of("outcome", "episode", "learned_fact", "learning_digest", "decision_record").contains(table)) throw new IllegalArgumentException(table);
        String sql = (dryRun ? "select count(*) from " : "delete from ") + table + " where " + where;
        var spec = jdbc.sql(sql).param("t", tenant).param("c", cutoff);
        return dryRun ? spec.query(Long.class).single() : spec.update();
    }
}
