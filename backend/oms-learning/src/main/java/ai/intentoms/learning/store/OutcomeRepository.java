package ai.intentoms.learning.store;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Persistence for {@code outcome} (OC-03). Insert-only for distinct events: an episode accumulates several outcome
 * rows over time, one per (episode, source event) pair ({@link #insert}'s conflict target). The one exception is
 * {@link #updatePayload}, used only to fold a return's disposition and refund events into a single row per RMA
 * ({@code OutcomeObserver.mergeIntoReturn}) rather than letting them count as two outcomes of the same loss — unlike
 * {@code decision_record} there is no DB trigger blocking that update, so this comment is the contract (D5-4).
 */
@Repository
public class OutcomeRepository {
    private final JdbcClient jdbc;
    public OutcomeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Returns false when this (episode, source event) pair is already stored: a redelivered event is a no-op. */
    public boolean insert(OutcomeRecord o) {
        return jdbc.sql("""
            insert into outcome(outcome_id, tenant_id, episode_id, type, payload, observed_at, source_event_id, created_at)
            values (:id, :t, :ep, :type, :payload, :obs, :src, :created)
            on conflict (tenant_id, episode_id, source_event_id) where source_event_id is not null do nothing
            """)
            .param("id", o.outcomeId()).param("t", TenantContext.require()).param("ep", o.episodeId()).param("type", o.type())
            .param("payload", Jsonb.of(o.payload())).param("obs", Timestamp.from(o.observedAt())).param("src", o.sourceEventId())
            .param("created", Timestamp.from(o.createdAt())).update() == 1;
    }

    /**
     * Replaces the payload of one outcome (a return's events merged into its single outcome). {@code created_at} moves
     * to now: the outcome changed, and the reward sweep recomputes an episode whose outcomes are newer than its reward.
     */
    public void updatePayload(String outcomeId, OutcomeData payload) {
        jdbc.sql("update outcome set payload = :payload, created_at = greatest(created_at, :now) where tenant_id = :t and outcome_id = :id")
            .param("payload", Jsonb.of(payload)).param("now", Timestamp.from(Instant.now())).param("t", TenantContext.require()).param("id", outcomeId).update();
    }

    /**
     * The grand total of the order an episode's outcomes are about, for an episode whose outcomes carry no order value
     * (opened after shipment, so it never saw {@code order.shipped}; a return's decisions have the return, not the
     * order, as subject). The order is the episode's own when it has one, else the one its outcome events name. Empty
     * when the order module is not deployed: checked first, because a failed statement would abort the caller's
     * transaction.
     */
    public java.util.Optional<Double> orderValueForEpisode(String episodeId, String orderId) {
        if (ordersTable == null) ordersTable = jdbc.sql("select to_regclass('orders') is not null").query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ordersTable)) return java.util.Optional.empty();
        String t = TenantContext.require();
        java.util.Optional<Double> own = orderId == null ? java.util.Optional.empty() : grandTotal(t, orderId);
        if (own.isPresent()) return own;
        List<String> named = jdbc.sql("""
            select distinct coalesce(e.payload->>'orderId', e.correlation_id) from outcome x join event e on e.event_id = x.source_event_id and e.tenant_id = x.tenant_id
            where x.tenant_id = :t and x.episode_id = :ep and coalesce(e.payload->>'orderId', e.correlation_id) is not null
            """).param("t", t).param("ep", episodeId).query(String.class).list();
        for (String o : named) {
            java.util.Optional<Double> v = grandTotal(t, o);
            if (v.isPresent()) return v;
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<Double> grandTotal(String tenant, String orderId) {
        return jdbc.sql("select grand_total from orders where tenant_id = :t and id = :o").param("t", tenant).param("o", orderId)
            .query(java.math.BigDecimal.class).list().stream().filter(java.util.Objects::nonNull).findFirst().map(java.math.BigDecimal::doubleValue);
    }

    private volatile Boolean ordersTable;

    public List<OutcomeRecord> forEpisode(String episodeId) {
        String t = TenantContext.require();
        return jdbc.sql("select outcome_id, episode_id, type, payload::text as payload, observed_at, source_event_id, created_at from outcome where tenant_id = :t and episode_id = :ep order by observed_at")
            .param("t", t).param("ep", episodeId).query().listOfRows().stream().map(OutcomeRepository::hydrate).toList();
    }

    /** Idempotency for re-delivered events: one outcome per (source event, episode) — the same event legitimately
     * attaches to every open episode for the order. */
    public boolean existsForEventAndEpisode(String sourceEventId, String episodeId) {
        String t = TenantContext.require();
        return jdbc.sql("select count(*) from outcome where tenant_id = :t and source_event_id = :e and episode_id = :ep")
            .param("t", t).param("e", sourceEventId).param("ep", episodeId).query(Long.class).single() > 0;
    }

    public boolean existsForEvent(String sourceEventId) {
        String t = TenantContext.require();
        return jdbc.sql("select count(*) from outcome where tenant_id = :t and source_event_id = :e").param("t", t).param("e", sourceEventId).query(Long.class).single() > 0;
    }

    private static OutcomeRecord hydrate(Map<String, Object> r) {
        OutcomeData payload = Json.read((String) r.get("payload"), OutcomeData.class);
        return new OutcomeRecord((String) r.get("outcome_id"), (String) r.get("episode_id"), (String) r.get("type"), payload,
            ((Timestamp) r.get("observed_at")).toInstant(), (String) r.get("source_event_id"), ((Timestamp) r.get("created_at")).toInstant());
    }
}
