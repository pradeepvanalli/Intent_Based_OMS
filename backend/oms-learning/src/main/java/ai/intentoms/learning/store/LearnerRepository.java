package ai.intentoms.learning.store;

import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Per-tenant governance for every registered learner (GV-01..GV-03, table {@code learner}). A row is created
 * on first touch via {@link #getOrCreate}, defaulting to L1 (recommend-only), matching the requirements doc's
 * stated default autonomy level for a new learner. */
@Repository
public class LearnerRepository {
    private final JdbcClient jdbc;
    public LearnerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "learner_key, autonomy_level, enabled, max_promotions_per_week, max_step, last_run_at, updated_by, updated_at, paused_reason";

    public LearnerState getOrCreate(String learnerKey) {
        String t = TenantContext.require();
        var existing = jdbc.sql("select " + COLS + " from learner where tenant_id = :t and learner_key = :k").param("t", t).param("k", learnerKey)
            .query().listOfRows().stream().findFirst();
        if (existing.isPresent()) return hydrate(existing.get());
        LearnerState fresh = LearnerState.defaultFor(learnerKey);
        upsert(fresh);
        return fresh;
    }

    /** Registers a learner with its declared default autonomy; an existing row (a human's settings) is left alone. */
    public boolean insertIfAbsent(String learnerKey, AutonomyLevel defaultLevel) {
        LearnerState s = LearnerState.defaultFor(learnerKey, defaultLevel);
        return jdbc.sql("""
            insert into learner(learner_key, tenant_id, autonomy_level, enabled, max_promotions_per_week, max_step, last_run_at, updated_by, updated_at)
            values (:k, :t, :level, true, :max, null, null, 'system', now()) on conflict (tenant_id, learner_key) do nothing
            """).param("k", learnerKey).param("t", TenantContext.require()).param("level", s.autonomyLevel().name()).param("max", s.maxPromotionsPerWeek()).update() == 1;
    }

    public void pause(String learnerKey, String reason, String by, Instant at) {
        getOrCreate(learnerKey);
        jdbc.sql("update learner set paused_reason = :r, paused_at = :at, paused_by = :by, updated_by = :by, updated_at = :at where tenant_id = :t and learner_key = :k")
            .param("r", reason).param("at", Timestamp.from(at)).param("by", by).param("t", TenantContext.require()).param("k", learnerKey).update();
    }

    public void resume(String learnerKey, String by, Instant at) {
        jdbc.sql("update learner set paused_reason = null, paused_at = null, paused_by = null, updated_by = :by, updated_at = :at where tenant_id = :t and learner_key = :k")
            .param("at", Timestamp.from(at)).param("by", by).param("t", TenantContext.require()).param("k", learnerKey).update();
    }

    public void upsert(LearnerState s) {
        jdbc.sql("""
            insert into learner(learner_key, tenant_id, autonomy_level, enabled, max_promotions_per_week, max_step, last_run_at, updated_by, updated_at)
            values (:k, :t, :level, :enabled, :max, :step, :last, :by, :at)
            on conflict (tenant_id, learner_key) do update set autonomy_level = :level, enabled = :enabled, max_promotions_per_week = :max, max_step = :step,
              last_run_at = :last, updated_by = :by, updated_at = :at
            """)
            .param("k", s.learnerKey()).param("t", TenantContext.require()).param("level", s.autonomyLevel().name()).param("enabled", s.enabled())
            .param("max", s.maxPromotionsPerWeek()).param("step", s.maxStep()).param("last", s.lastRunAt() == null ? null : Timestamp.from(s.lastRunAt()))
            .param("by", s.updatedBy()).param("at", Timestamp.from(s.updatedAt())).update();
    }

    public List<LearnerState> all() {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from learner where tenant_id = :t order by learner_key").param("t", t).query().listOfRows().stream().map(LearnerRepository::hydrate).toList();
    }

    public void setAutonomy(String learnerKey, AutonomyLevel level, String by, Instant at) {
        LearnerState s = getOrCreate(learnerKey);
        upsert(new LearnerState(learnerKey, level, s.enabled(), s.maxPromotionsPerWeek(), s.maxStep(), s.lastRunAt(), by, at));
    }

    public void killSwitch(String learnerKey, boolean enabled, String by, Instant at) {
        LearnerState s = getOrCreate(learnerKey);
        upsert(new LearnerState(learnerKey, s.autonomyLevel(), enabled, s.maxPromotionsPerWeek(), s.maxStep(), s.lastRunAt(), by, at));
    }

    public void recordRun(String learnerKey, Instant at) {
        LearnerState s = getOrCreate(learnerKey);
        upsert(new LearnerState(learnerKey, s.autonomyLevel(), s.enabled(), s.maxPromotionsPerWeek(), s.maxStep(), at, s.updatedBy(), at));
    }

    private static LearnerState hydrate(Map<String, Object> r) {
        return new LearnerState((String) r.get("learner_key"), AutonomyLevel.valueOf((String) r.get("autonomy_level")), (Boolean) r.get("enabled"),
            (Integer) r.get("max_promotions_per_week"), (Double) r.get("max_step"), ts(r.get("last_run_at")), (String) r.get("updated_by"), ts(r.get("updated_at")), (String) r.get("paused_reason"));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
