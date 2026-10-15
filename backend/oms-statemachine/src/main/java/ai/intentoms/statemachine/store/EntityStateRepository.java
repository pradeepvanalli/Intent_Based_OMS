package ai.intentoms.statemachine.store;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.statemachine.model.QuantityBucket;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence of entity status: one {@code entity_state} row per entity plus {@code entity_state_qty} buckets
 * (FR-SM-02, FR-SM-06). {@link #lock} takes the row lock that serialises concurrent transitions on one entity.
 */
@Repository
public class EntityStateRepository {
    private final JdbcClient jdbc;

    /** Mirror of an {@code entity_state} row. */
    public record Row(String entityType, String entityId, String parentId, String modelId, int modelVersion, String state,
                      Map<String, Object> attributes, Instant createdAt, Instant updatedAt) {}

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("parent_id"),
        rs.getString("model_id"), rs.getInt("model_version"), rs.getString("state"), Json.readMap(rs.getString("attributes")),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<QuantityBucket> BUCKET = (rs, i) -> new QuantityBucket(rs.getString("state"), rs.getInt("qty"), rs.getTimestamp("since").toInstant());

    private static final String SELECT = "select entity_type, entity_id, parent_id, model_id, model_version, state, attributes::text as attributes, created_at, updated_at from entity_state";

    public EntityStateRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Parent id for hierarchical ids ("<orderId>:<lineId>" of a *_line entity type), else null. */
    public static String parentIdOf(String entityType, String entityId) {
        if (entityType == null || entityId == null || !entityType.endsWith("_line")) return null;
        int i = entityId.indexOf(':');
        return i > 0 ? entityId.substring(0, i) : null;
    }

    /** Entity type of the header for a *_line type ("order_line" → "order"), else null. */
    public static String headerTypeOf(String entityType) {
        return entityType != null && entityType.endsWith("_line") ? entityType.substring(0, entityType.length() - "_line".length()) : null;
    }

    public void insert(String entityType, String entityId, String modelId, int modelVersion, String state, Map<String, Object> attributes, Instant now) {
        jdbc.sql("""
            insert into entity_state(tenant_id, entity_type, entity_id, parent_id, model_id, model_version, state, attributes, created_at, updated_at)
            values (:t, :et, :eid, :pid, :m, :v, :s, :a, :now, :now)
            """)
            .param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).param("pid", parentIdOf(entityType, entityId))
            .param("m", modelId).param("v", modelVersion).param("s", state).param("a", Jsonb.of(attributes == null ? Map.of() : attributes))
            .param("now", Timestamp.from(now)).update();
    }

    public Optional<Row> find(String entityType, String entityId) {
        return jdbc.sql(SELECT + " where tenant_id = :t and entity_type = :et and entity_id = :eid")
            .param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).query(ROW).optional();
    }

    /** SELECT ... FOR UPDATE: serialises concurrent transitions on the same entity. */
    public Optional<Row> lock(String entityType, String entityId) {
        return jdbc.sql(SELECT + " where tenant_id = :t and entity_type = :et and entity_id = :eid for update")
            .param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).query(ROW).optional();
    }

    public List<Row> findAll(String entityType, Collection<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " where tenant_id = :t and entity_type = :et and entity_id in (:ids)")
            .param("t", TenantContext.require()).param("et", entityType).param("ids", new ArrayList<>(entityIds)).query(ROW).list();
    }

    public List<Row> byModelVersion(String modelId, int version, boolean forUpdate) {
        return jdbc.sql(SELECT + " where tenant_id = :t and model_id = :m and model_version = :v order by entity_type, entity_id" + (forUpdate ? " for update" : ""))
            .param("t", TenantContext.require()).param("m", modelId).param("v", version).query(ROW).list();
    }

    public void updateState(String entityType, String entityId, String state, Instant now) {
        jdbc.sql("update entity_state set state = :s, updated_at = :now where tenant_id = :t and entity_type = :et and entity_id = :eid")
            .param("s", state).param("now", Timestamp.from(now)).param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).update();
    }

    public void updateVersion(String entityType, String entityId, int modelVersion, Instant now) {
        jdbc.sql("update entity_state set model_version = :v, updated_at = :now where tenant_id = :t and entity_type = :et and entity_id = :eid")
            .param("v", modelVersion).param("now", Timestamp.from(now)).param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).update();
    }

    public List<QuantityBucket> buckets(String entityType, String entityId) {
        return jdbc.sql("select state, qty, since from entity_state_qty where tenant_id = :t and entity_type = :et and entity_id = :eid order by since, state")
            .param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).query(BUCKET).list();
    }

    /** Buckets for many entities at once: entityId → buckets. */
    public Map<String, List<QuantityBucket>> bucketsFor(String entityType, Collection<String> entityIds) {
        Map<String, List<QuantityBucket>> out = new LinkedHashMap<>();
        if (entityIds == null || entityIds.isEmpty()) return out;
        jdbc.sql("select entity_id, state, qty, since from entity_state_qty where tenant_id = :t and entity_type = :et and entity_id in (:ids) order by entity_id, since, state")
            .param("t", TenantContext.require()).param("et", entityType).param("ids", new ArrayList<>(entityIds))
            .query((rs, i) -> Map.entry(rs.getString("entity_id"), new QuantityBucket(rs.getString("state"), rs.getInt("qty"), rs.getTimestamp("since").toInstant())))
            .list()
            .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    /** Insert or overwrite one bucket; qty 0 deletes it. */
    public void putBucket(String entityType, String entityId, String state, int qty, Instant since) {
        String t = TenantContext.require();
        if (qty <= 0) {
            jdbc.sql("delete from entity_state_qty where tenant_id = :t and entity_type = :et and entity_id = :eid and state = :s")
                .param("t", t).param("et", entityType).param("eid", entityId).param("s", state).update();
            return;
        }
        jdbc.sql("""
            insert into entity_state_qty(tenant_id, entity_type, entity_id, state, qty, since) values (:t, :et, :eid, :s, :q, :since)
            on conflict (tenant_id, entity_type, entity_id, state) do update set qty = excluded.qty
            """)
            .param("t", t).param("et", entityType).param("eid", entityId).param("s", state).param("q", qty).param("since", Timestamp.from(since)).update();
    }

    /** Sum of child quantities per state for all {@code childType} entities whose parent is {@code parentId} (FR-SM-05). */
    public Map<String, Integer> childQtyByState(String childType, String parentId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("""
            select q.state, sum(q.qty) as qty from entity_state e
            join entity_state_qty q on q.tenant_id = e.tenant_id and q.entity_type = e.entity_type and q.entity_id = e.entity_id
            where e.tenant_id = :t and e.entity_type = :ct and e.parent_id = :p group by q.state order by q.state
            """)
            .param("t", TenantContext.require()).param("ct", childType).param("p", parentId)
            .query((rs, i) -> Map.entry(rs.getString("state"), rs.getInt("qty"))).list()
            .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Distinct model versions in use by children of a parent (to zero-fill roll-up state maps). */
    public List<Row> children(String childType, String parentId) {
        return jdbc.sql(SELECT + " where tenant_id = :t and entity_type = :ct and parent_id = :p order by entity_id")
            .param("t", TenantContext.require()).param("ct", childType).param("p", parentId).query(ROW).list();
    }
}
