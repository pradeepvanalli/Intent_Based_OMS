package ai.intentoms.inventory.store;

import ai.intentoms.inventory.model.InventoryPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQL for {@code inventory_position}. Every statement is scoped by tenant; the aggregation queries are shaped so the
 * planner can satisfy them from the covering primary key and the partial active-reservation index (NFR-01).
 */
@Repository
public class InventoryPositionStore {
    /** Supply rows carry this sentinel in {@code demand_type} so it can be part of the primary key. */
    public static final String NO_DEMAND = "";

    /** Aggregated raw components for one (sku, node); {@code reserved} is the active internal reservation sum. */
    public record AtpRow(String sku, String nodeId, int onHand, int inbound, int externalReserved, int allocated, int safety, double confidence, int reserved) {}

    /** The locked on-hand supply row. */
    public record OnHandRow(int qty, double confidence) {}

    private static final RowMapper<AtpRow> ATP_ROW = (rs, i) -> new AtpRow(rs.getString("sku"), rs.getString("node_id"), rs.getInt("on_hand"),
        rs.getInt("inbound"), rs.getInt("ext_reserved"), rs.getInt("allocated"), rs.getInt("safety"), rs.getDouble("confidence"), rs.getInt("reserved"));

    private static final String ATP_SELECT = """
        select p.sku, p.node_id,
               coalesce(sum(case when p.demand_type = '' and p.supply_type = 'on_hand' then p.qty end), 0)  as on_hand,
               coalesce(sum(case when p.demand_type = '' and p.supply_type <> 'on_hand' then p.qty end), 0) as inbound,
               coalesce(sum(case when p.demand_type = 'reserved' then p.qty end), 0)                        as ext_reserved,
               coalesce(sum(case when p.demand_type = 'allocated' then p.qty end), 0)                       as allocated,
               coalesce(sum(case when p.demand_type = 'safety' then p.qty end), 0)                          as safety,
               coalesce(max(case when p.demand_type = '' and p.supply_type = 'on_hand' then p.confidence end), 1.0) as confidence,
               coalesce(max(r.reserved), 0)                                                                 as reserved
        from inventory_position p
        left join (select sku, node_id, sum(qty) as reserved from inventory_reservation
                   where tenant_id = :t and %s and status = 'ACTIVE' group by sku, node_id) r
               on r.sku = p.sku and r.node_id = p.node_id
        where p.tenant_id = :t and %s
        group by p.sku, p.node_id
        order by p.sku, p.node_id
        """;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public InventoryPositionStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** ATP components for every node holding the sku, or for one node when {@code nodeId} is non-null. */
    public List<AtpRow> atpRows(String tenant, String sku, String nodeId) {
        String inner = nodeId == null ? "sku = :sku" : "sku = :sku and node_id = :node";
        String outer = nodeId == null ? "p.sku = :sku" : "p.sku = :sku and p.node_id = :node";
        var q = jdbc.sql(ATP_SELECT.formatted(inner, outer)).param("t", tenant).param("sku", sku);
        if (nodeId != null) q = q.param("node", nodeId);
        return q.query(ATP_ROW).list();
    }

    /** ATP components for several skus in one round trip (network availability). */
    public List<AtpRow> atpRows(String tenant, Collection<String> skus) {
        if (skus.isEmpty()) return List.of();
        return jdbc.sql(ATP_SELECT.formatted("sku in (:skus)", "p.sku in (:skus)"))
            .param("t", tenant).param("skus", List.copyOf(skus)).query(ATP_ROW).list();
    }

    /** Row-locks the on-hand supply row of (sku, node) for the current transaction (NFR-06). Empty when unknown. */
    public Optional<OnHandRow> lockOnHand(String tenant, String sku, String nodeId) {
        return jdbc.sql("select qty, confidence from inventory_position where tenant_id = :t and sku = :s and node_id = :n and supply_type = 'on_hand' and demand_type = '' for update")
            .param("t", tenant).param("s", sku).param("n", nodeId)
            .query((rs, i) -> new OnHandRow(rs.getInt("qty"), rs.getDouble("confidence"))).optional();
    }

    /**
     * Bulk upsert in one JDBC batch. Feeds older than the stored {@code as_of} are ignored (out-of-order sync messages,
     * FR-INV-04); confidence {@code <= 0} keeps the stored confidence (FR-INV-05). Returns the number of rows written.
     *
     * <p>IU-03: an on-hand row's {@code received_at} (oldest unit) is the feed's {@code receivedAt} when given; otherwise
     * it is set when on-hand goes from zero to positive, kept while stock remains, and cleared at zero. Inbound rows keep
     * the feed's {@code supplierId} (IU-05).
     */
    public int upsertAll(String tenant, List<InventoryPosition> positions, Instant now) {
        if (positions.isEmpty()) return 0;
        int[][] counts = template.batchUpdate("""
            insert into inventory_position(tenant_id, sku, node_id, supply_type, demand_type, qty, confidence, as_of, updated_at, received_at, supplier_id)
            values (?, ?, ?, ?, ?, ?, coalesce(nullif(?, 0.0), 1.0), ?, ?, ?, ?)
            on conflict (tenant_id, sku, node_id, supply_type, demand_type) do update
               set qty = excluded.qty,
                   confidence = coalesce(nullif(?, 0.0), inventory_position.confidence),
                   as_of = excluded.as_of,
                   updated_at = excluded.updated_at,
                   received_at = case
                       when excluded.supply_type <> 'on_hand' or excluded.demand_type <> '' then inventory_position.received_at
                       when excluded.qty <= 0 then null
                       when cast(? as timestamptz) is not null then cast(? as timestamptz)
                       when inventory_position.qty <= 0 or inventory_position.received_at is null then excluded.as_of
                       else inventory_position.received_at end,
                   supplier_id = coalesce(excluded.supplier_id, inventory_position.supplier_id)
             where excluded.as_of >= inventory_position.as_of
            """, positions, positions.size(), (ps, p) -> {
            double conf = Math.max(0.0, p.confidence());
            String demand = p.demandType() == null ? NO_DEMAND : p.demandType();
            boolean stocked = InventoryPosition.ON_HAND.equals(p.supplyType()) && NO_DEMAND.equals(demand) && p.qty() > 0;
            Timestamp explicit = p.receivedAt() == null ? null : Timestamp.from(p.receivedAt());
            ps.setString(1, tenant);
            ps.setString(2, p.sku());
            ps.setString(3, p.nodeId());
            ps.setString(4, p.supplyType());
            ps.setString(5, demand);
            ps.setInt(6, p.qty());
            ps.setDouble(7, conf);
            ps.setTimestamp(8, Timestamp.from(p.asOf()));
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, stocked ? (explicit != null ? explicit : Timestamp.from(p.asOf())) : null);
            ps.setString(11, p.supplierId());
            ps.setDouble(12, conf);
            ps.setTimestamp(13, explicit);
            ps.setTimestamp(14, explicit);
        });
        int written = 0;
        for (int[] chunk : counts) for (int c : chunk) if (c > 0 || c == java.sql.Statement.SUCCESS_NO_INFO) written++;
        return written;
    }

    /** Adds {@code delta} to on-hand (creating the row at {@code delta} when absent) and returns the new quantity. Caller holds the row lock. */
    public int addOnHand(String tenant, String sku, String nodeId, int delta, Instant now) {
        return jdbc.sql("""
            insert into inventory_position(tenant_id, sku, node_id, supply_type, demand_type, qty, as_of, updated_at, received_at)
            values (:t, :s, :n, 'on_hand', '', :d, :now, :now, case when :d > 0 then cast(:now as timestamptz) end)
            on conflict (tenant_id, sku, node_id, supply_type, demand_type) do update
               set qty = inventory_position.qty + :d, as_of = :now, updated_at = :now,
                   received_at = case when inventory_position.qty + :d <= 0 then null
                                      when inventory_position.qty <= 0 or inventory_position.received_at is null then cast(:now as timestamptz)
                                      else inventory_position.received_at end
            returning qty
            """).param("t", tenant).param("s", sku).param("n", nodeId).param("d", delta).param("now", Timestamp.from(now)).query(Integer.class).single();
    }

    /** Stored on-hand quantity per {@code sku|node} key for the given skus (reconciliation). */
    public Map<String, Integer> onHandBySkuNode(String tenant, Collection<String> skus) {
        Map<String, Integer> out = new HashMap<>();
        if (skus.isEmpty()) return out;
        jdbc.sql("select sku, node_id, qty from inventory_position where tenant_id = :t and sku in (:skus) and supply_type = 'on_hand' and demand_type = ''")
            .param("t", tenant).param("skus", List.copyOf(skus)).query().listOfRows()
            .forEach(r -> out.put(r.get("sku") + "|" + r.get("node_id"), ((Number) r.get("qty")).intValue()));
        return out;
    }

    /** IU-05: inbound (po / asn / in_transit) quantity per supplier for (sku, node); supplier null when the feed gave none. */
    public record InboundRow(String sku, String nodeId, String supplierId, int qty) {}

    public List<InboundRow> inboundRows(String tenant, Collection<String> skus) {
        if (skus.isEmpty()) return List.of();
        return jdbc.sql("""
            select sku, node_id, supplier_id, sum(qty) as qty from inventory_position
            where tenant_id = :t and sku in (:skus) and demand_type = '' and supply_type <> 'on_hand' and qty > 0
            group by sku, node_id, supplier_id
            """).param("t", tenant).param("skus", List.copyOf(skus))
            .query((rs, i) -> new InboundRow(rs.getString("sku"), rs.getString("node_id"), rs.getString("supplier_id"), rs.getInt("qty"))).list();
    }

    /** IU-03: when the oldest on-hand unit of (sku, node) arrived; empty when there is no stock. */
    public Optional<Instant> receivedAt(String tenant, String sku, String nodeId) {
        return jdbc.sql("select received_at from inventory_position where tenant_id = :t and sku = :s and node_id = :n and supply_type = 'on_hand' and demand_type = '' and qty > 0")
            .param("t", tenant).param("s", sku).param("n", nodeId).query((rs, i) -> Optional.ofNullable(rs.getTimestamp(1))).optional()
            .flatMap(o -> o).map(Timestamp::toInstant);
    }

    /** Writes the confidence of the on-hand row (FR-INV-05). Returns false when the position does not exist. */
    public boolean setConfidence(String tenant, String sku, String nodeId, double confidence, Instant now) {
        return jdbc.sql("update inventory_position set confidence = :c, updated_at = :now where tenant_id = :t and sku = :s and node_id = :n and supply_type = 'on_hand' and demand_type = ''")
            .param("c", confidence).param("now", Timestamp.from(now)).param("t", tenant).param("s", sku).param("n", nodeId).update() > 0;
    }
}
