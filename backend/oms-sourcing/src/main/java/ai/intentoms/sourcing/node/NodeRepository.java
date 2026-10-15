package ai.intentoms.sourcing.node;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.sourcing.model.Node;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQL for {@code node} and {@code node_penalty}. Penalty rows are raw (undecayed); the registry applies the decay. */
@Repository
public class NodeRepository {
    /** Sentinel sku for the node-level penalty row. */
    public static final String NODE_LEVEL = "*";

    /** Raw penalty row: {@code penalty} as written at {@code updatedAt}; the effective value decays from there. */
    public record PenaltyRow(String nodeId, String sku, double penalty, String reason, Instant updatedAt) {}

    private final JdbcClient jdbc;

    public NodeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Node> find(String id) {
        return jdbc.sql("select definition::text as d from node where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id)
            .query(String.class).optional().map(NodeRepository::parse);
    }

    public List<Node> all(boolean activeOnly) {
        var q = jdbc.sql("select definition::text as d from node where tenant_id = :t" + (activeOnly ? " and active = true" : "") + " order by id").param("t", TenantContext.require());
        return q.query(String.class).list().stream().map(NodeRepository::parse).toList();
    }

    public void upsert(Node n) {
        Map<String, Object> def = Json.toMap(n);
        def.remove("penalty");
        jdbc.sql("""
            insert into node(tenant_id, id, type, active, definition, updated_at) values (:t, :id, :type, :active, :d, now())
            on conflict (tenant_id, id) do update set type = excluded.type, active = excluded.active, definition = excluded.definition, updated_at = now()
            """).param("t", TenantContext.require()).param("id", n.id()).param("type", n.type()).param("active", n.active()).param("d", Jsonb.of(def)).update();
    }

    public List<PenaltyRow> penalties(String nodeId) {
        return jdbc.sql("select node_id, sku, penalty, reason, updated_at from node_penalty where tenant_id = :t and node_id = :n order by sku")
            .param("t", TenantContext.require()).param("n", nodeId).query().listOfRows().stream().map(NodeRepository::row).toList();
    }

    public List<PenaltyRow> allPenalties() {
        return jdbc.sql("select node_id, sku, penalty, reason, updated_at from node_penalty where tenant_id = :t order by node_id, sku")
            .param("t", TenantContext.require()).query().listOfRows().stream().map(NodeRepository::row).toList();
    }

    /** Writes the (already decayed and summed) new raw penalty with the current instant as its reference time. */
    /** Removes every learned penalty for a node (node-level and per SKU). Returns the number of rows cleared. */
    public int clearPenalties(String nodeId) {
        return jdbc.sql("delete from node_penalty where tenant_id = :t and node_id = :n").param("t", TenantContext.require()).param("n", nodeId).update();
    }

    public void writePenalty(String nodeId, String sku, double penalty, String reason, Instant at) {
        jdbc.sql("""
            insert into node_penalty(tenant_id, node_id, sku, penalty, reason, updated_at) values (:t, :n, :s, :p, :r, :at)
            on conflict (tenant_id, node_id, sku) do update set penalty = excluded.penalty, reason = excluded.reason, updated_at = excluded.updated_at
            """).param("t", TenantContext.require()).param("n", nodeId).param("s", sku == null ? NODE_LEVEL : sku).param("p", penalty).param("r", reason).param("at", Timestamp.from(at)).update();
    }

    private static Node parse(String json) { return Json.read(json, Node.class); }

    private static PenaltyRow row(Map<String, Object> r) {
        String sku = (String) r.get("sku");
        return new PenaltyRow((String) r.get("node_id"), NODE_LEVEL.equals(sku) ? null : sku, ((Number) r.get("penalty")).doubleValue(), (String) r.get("reason"), ((Timestamp) r.get("updated_at")).toInstant());
    }
}
