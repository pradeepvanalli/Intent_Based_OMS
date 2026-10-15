package ai.intentoms.platform.ledger;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Transactional
public class JdbcEventStore implements EventStore {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    static final RowMapper<Event> MAPPER = (rs, i) -> new Event(
        rs.getString("event_id"), rs.getString("tenant_id"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
        rs.getLong("sequence"), rs.getString("type"), Json.readMap(rs.getString("payload")), Actor.parse(rs.getString("actor")),
        rs.getString("causation_id"), rs.getString("correlation_id"), Json.readMap(rs.getString("metadata")),
        rs.getTimestamp("occurred_at").toInstant());

    public JdbcEventStore(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** Convenience overload declared here (not only as an interface default) so the transactional proxy applies. */
    @Override
    public Event append(String aggregateType, String aggregateId, NewEvent event) {
        return append(aggregateType, aggregateId, -1, List.of(event)).get(0);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public List<Event> append(String aggregateType, String aggregateId, long expectedVersion, List<NewEvent> events) {
        String tenant = TenantContext.require();
        long current = lockAndGetVersion(tenant, aggregateType, aggregateId);
        if (expectedVersion >= 0 && expectedVersion != current) {
            throw new ConflictException("Aggregate " + aggregateType + "/" + aggregateId + " is at version " + current + ", expected " + expectedVersion,
                Map.of("aggregateType", aggregateType, "aggregateId", aggregateId, "currentVersion", current));
        }
        List<Event> out = new ArrayList<>(events.size());
        long seq = current;
        Instant now = Instant.now();
        for (NewEvent ne : events) {
            seq++;
            String id = Ids.id();
            String correlation = ne.correlationId() != null ? ne.correlationId() : aggregateId;
            Event e = new Event(id, tenant, aggregateType, aggregateId, seq, ne.type(), ne.payload() == null ? Map.of() : ne.payload(),
                ne.actor() == null ? Actor.system("unknown") : ne.actor(), ne.causationId(), correlation, ne.metadata() == null ? Map.of() : ne.metadata(), now);
            try {
                jdbc.sql("""
                    insert into event(event_id, tenant_id, aggregate_type, aggregate_id, sequence, type, payload, actor, causation_id, correlation_id, metadata, occurred_at)
                    values (:id, :tenant, :at, :aid, :seq, :type, :payload, :actor, :cause, :corr, :meta, :ts)
                    """)
                    .param("id", id).param("tenant", tenant).param("at", aggregateType).param("aid", aggregateId).param("seq", seq)
                    .param("type", e.type()).param("payload", Jsonb.of(e.payload())).param("actor", e.actor().ref())
                    .param("cause", e.causationId()).param("corr", e.correlationId()).param("meta", Jsonb.of(e.metadata())).param("ts", Timestamp.from(now))
                    .update();
            } catch (DuplicateKeyException dup) {
                throw new ConflictException("Concurrent append on " + aggregateType + "/" + aggregateId);
            }
            outbox.enqueue(e);
            out.add(e);
        }
        jdbc.sql("update aggregate_version set version = :v, updated_at = now() where tenant_id = :t and aggregate_type = :at and aggregate_id = :aid")
            .param("v", seq).param("t", tenant).param("at", aggregateType).param("aid", aggregateId).update();
        return out;
    }

    /**
     * Serialises appends per aggregate. The first writer creates the version row with an upsert that is a no-op
     * for concurrent creators, then every writer takes the row lock, so sequences are gap-free and unique even
     * under contention (NFR-06). Requires the surrounding @Transactional (REQUIRED) context.
     */
    private long lockAndGetVersion(String tenant, String aggregateType, String aggregateId) {
        jdbc.sql("insert into aggregate_version(tenant_id, aggregate_type, aggregate_id, version) values (:t, :at, :aid, 0) on conflict do nothing")
            .param("t", tenant).param("at", aggregateType).param("aid", aggregateId).update();
        return jdbc.sql("select version from aggregate_version where tenant_id = :t and aggregate_type = :at and aggregate_id = :aid for update")
            .param("t", tenant).param("at", aggregateType).param("aid", aggregateId).query(Long.class).single();
    }

    @Override
    public List<Event> load(String aggregateType, String aggregateId) { return loadFrom(aggregateType, aggregateId, 0); }

    @Override
    public List<Event> loadFrom(String aggregateType, String aggregateId, long fromSequenceExclusive) {
        return jdbc.sql("select * from event where tenant_id = :t and aggregate_type = :at and aggregate_id = :aid and sequence > :s order by sequence")
            .param("t", TenantContext.require()).param("at", aggregateType).param("aid", aggregateId).param("s", fromSequenceExclusive)
            .query(MAPPER).list();
    }

    @Override
    public long currentVersion(String aggregateType, String aggregateId) {
        return jdbc.sql("select version from aggregate_version where tenant_id = :t and aggregate_type = :at and aggregate_id = :aid")
            .param("t", TenantContext.require()).param("at", aggregateType).param("aid", aggregateId).query(Long.class).optional().orElse(0L);
    }

    @Override
    public Optional<Event> byId(String eventId) {
        return jdbc.sql("select * from event where tenant_id = :t and event_id = :id").param("t", TenantContext.require()).param("id", eventId).query(MAPPER).optional();
    }

    @Override
    public List<Event> scan(long afterPosition, int limit, List<String> typePrefixes) {
        StringBuilder sql = new StringBuilder("select * from event where tenant_id = :t and position > :p");
        if (typePrefixes != null && !typePrefixes.isEmpty()) {
            sql.append(" and (");
            for (int i = 0; i < typePrefixes.size(); i++) sql.append(i > 0 ? " or " : "").append("type like :tp").append(i);
            sql.append(")");
        }
        sql.append(" order by position limit :l");
        var q = jdbc.sql(sql.toString()).param("t", TenantContext.require()).param("p", afterPosition).param("l", limit);
        if (typePrefixes != null) for (int i = 0; i < typePrefixes.size(); i++) q = q.param("tp" + i, typePrefixes.get(i) + "%");
        return q.query(MAPPER).list();
    }

    @Override
    public List<Event> between(String aggregateType, Instant from, Instant to, List<String> types, int limit) {
        StringBuilder sql = new StringBuilder("select * from event where tenant_id = :t and aggregate_type = :at and occurred_at >= :f and occurred_at < :to");
        if (types != null && !types.isEmpty()) sql.append(" and type in (:types)");
        sql.append(" order by position limit :l");
        var q = jdbc.sql(sql.toString()).param("t", TenantContext.require()).param("at", aggregateType)
            .param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).param("l", limit);
        if (types != null && !types.isEmpty()) q = q.param("types", types);
        return q.query(MAPPER).list();
    }
}
