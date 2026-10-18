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

/** Persistence for {@code outcome} (OC-03). Append-only: an episode can have several outcomes over time. */
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
