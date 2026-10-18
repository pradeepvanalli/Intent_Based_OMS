package ai.intentoms.inventory.learning;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Read models the inventory learners need (SOO IU-01..IU-06), all tenant-scoped SQL over the inventory tables. The one
 * read outside this module's tables is {@link #skuOfLine}, which resolves a backordered line to its SKU from
 * {@code order_line} (a plain read; inventory has no compile-time dependency on the order module).
 */
@Repository
public class InventoryLearningRepository {
    private final JdbcClient jdbc;

    public InventoryLearningRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static String t() { return TenantContext.require(); }

    // ---- demand (IU-01, IU-04)

    /** Units committed to orders per (sku, node, day), from order-backed reservations. */
    public record DailyDemand(String sku, String nodeId, LocalDate day, int units) {}

    public List<DailyDemand> dailyDemand(Instant since) {
        return jdbc.sql("""
            select sku, node_id, (created_at at time zone 'UTC')::date as day, sum(qty) as units from inventory_reservation
            where tenant_id = :t and order_id is not null and status in ('ACTIVE', 'CONSUMED') and created_at >= :since
            group by sku, node_id, day
            """).param("t", t()).param("since", Timestamp.from(since))
            .query((rs, i) -> new DailyDemand(rs.getString("sku"), rs.getString("node_id"), rs.getDate("day").toLocalDate(), rs.getInt("units"))).list();
    }

    /** On-hand and static safety per (sku, node) for every stocked or safety-carrying position. */
    public record Stock(String sku, String nodeId, int onHand, int safety, Instant receivedAt) {}

    public List<Stock> stock() {
        return jdbc.sql("""
            select sku, node_id,
                   coalesce(sum(case when supply_type = 'on_hand' and demand_type = '' then qty end), 0) as on_hand,
                   coalesce(sum(case when demand_type = 'safety' then qty end), 0) as safety,
                   max(case when supply_type = 'on_hand' and demand_type = '' then received_at end) as received_at
            from inventory_position where tenant_id = :t
            group by sku, node_id
            """).param("t", t())
            .query((rs, i) -> new Stock(rs.getString("sku"), rs.getString("node_id"), rs.getInt("on_hand"), rs.getInt("safety"),
                rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant())).list();
    }

    // ---- stock age and stranded stock (IU-03)

    /** Most recent order that took this sku from another node since {@code since}: the sourcing that bypassed aged stock. */
    public Optional<String> recentOrderElsewhere(String sku, String nodeId, Instant since) {
        return jdbc.sql("""
            select order_id from inventory_reservation
            where tenant_id = :t and sku = :s and node_id <> :n and order_id is not null and created_at >= :since
            order by created_at desc limit 1
            """).param("t", t()).param("s", sku).param("n", nodeId).param("since", Timestamp.from(since)).query(String.class).optional();
    }

    /** Whether any order consumed this sku at this node since {@code since}. */
    public boolean soldSince(String sku, String nodeId, Instant since) {
        return jdbc.sql("""
            select count(*) from inventory_reservation
            where tenant_id = :t and sku = :s and node_id = :n and order_id is not null and created_at >= :since
            """).param("t", t()).param("s", sku).param("n", nodeId).param("since", Timestamp.from(since)).query(Long.class).single() > 0;
    }

    /** Records one stranded signal; false when this aged position was already signalled. */
    public boolean recordStranded(String sku, String nodeId, Instant receivedAt, int qty, int daysAged, String orderId) {
        return jdbc.sql("""
            insert into inventory_stranded_signal(tenant_id, sku, node_id, received_at, qty, days_aged, order_id)
            values (:t, :s, :n, :r, :q, :d, :o) on conflict do nothing
            """).param("t", t()).param("s", sku).param("n", nodeId).param("r", Timestamp.from(receivedAt)).param("q", qty).param("d", daysAged)
            .param("o", orderId).update() == 1;
    }

    // ---- reservation conversion (IU-02)

    /** Minutes from cart reservation to conversion, for carts converted since {@code since}. */
    public List<Double> conversionMinutes(Instant since) {
        return jdbc.sql("""
            select extract(epoch from (converted_at - created_at)) / 60.0 from inventory_reservation
            where tenant_id = :t and converted_at is not null and created_at >= :since
            """).param("t", t()).param("since", Timestamp.from(since)).query(Double.class).list();
    }

    /** Cart (non-order) reservations created since {@code since}: converted ones plus those that never became orders. */
    public long cartReservations(Instant since) {
        return jdbc.sql("""
            select count(*) from inventory_reservation
            where tenant_id = :t and created_at >= :since and (converted_at is not null or source <> 'order')
            """).param("t", t()).param("since", Timestamp.from(since)).query(Long.class).single();
    }

    // ---- supplier receipts (IU-05)

    public record Receipt(String supplierId, String asnId, String sku, String nodeId, int expectedQty, int receivedQty, Instant expectedAt, Instant receivedAt) {}

    /** Records an ASN receipt; a repeated (supplier, asn, sku, node) is ignored. Returns the receipt id or empty when duplicate. */
    public Optional<String> insertReceipt(Receipt r) {
        String id = Ids.prefixed("rcpt");
        int n = jdbc.sql("""
            insert into inventory_inbound_receipt(id, tenant_id, supplier_id, asn_id, sku, node_id, expected_qty, received_qty, expected_at, received_at)
            values (:id, :t, :sup, :asn, :s, :n, :e, :r, :ea, :ra) on conflict do nothing
            """).param("id", id).param("t", t()).param("sup", r.supplierId()).param("asn", r.asnId()).param("s", r.sku()).param("n", r.nodeId())
            .param("e", r.expectedQty()).param("r", r.receivedQty()).param("ea", Timestamp.from(r.expectedAt())).param("ra", Timestamp.from(r.receivedAt())).update();
        return n == 1 ? Optional.of(id) : Optional.empty();
    }

    public List<Receipt> receipts(Instant since) {
        return jdbc.sql("""
            select supplier_id, asn_id, sku, node_id, expected_qty, received_qty, expected_at, received_at from inventory_inbound_receipt
            where tenant_id = :t and received_at >= :since order by received_at
            """).param("t", t()).param("since", Timestamp.from(since))
            .query((rs, i) -> new Receipt(rs.getString("supplier_id"), rs.getString("asn_id"), rs.getString("sku"), rs.getString("node_id"),
                rs.getInt("expected_qty"), rs.getInt("received_qty"), rs.getTimestamp("expected_at").toInstant(), rs.getTimestamp("received_at").toInstant())).list();
    }

    // ---- backorders (IU-06)

    private volatile Boolean orderLineTable;

    /** The SKU of an order line, read from the order module's table when it exists (checked once, never assumed). */
    public Optional<String> skuOfLine(String orderId, String lineId) {
        if (orderLineTable == null) orderLineTable = jdbc.sql("select to_regclass('order_line') is not null").query(Boolean.class).single();
        if (!Boolean.TRUE.equals(orderLineTable)) return Optional.empty();
        return jdbc.sql("select sku from order_line where tenant_id = :t and order_id = :o and line_id = :l")
            .param("t", t()).param("o", orderId).param("l", lineId).query(String.class).optional();
    }

    /** Counts one backorder resolution exactly once per ledger event; false when this event was already counted. */
    public boolean recordBackorderResolution(String eventId, String orderId, String lineId, String sku, String outcome, Instant at) {
        int n = jdbc.sql("""
            insert into inventory_backorder_resolution(tenant_id, event_id, order_id, line_id, sku, outcome, resolved_at)
            values (:t, :e, :o, :l, :s, :out, :at) on conflict do nothing
            """).param("t", t()).param("e", eventId).param("o", orderId).param("l", lineId).param("s", sku).param("out", outcome)
            .param("at", Timestamp.from(at)).update();
        if (n == 0 || sku == null) return n == 1;
        jdbc.sql("""
            insert into inventory_backorder_stat(tenant_id, sku, converted, cancelled, updated_at)
            values (:t, :s, :c, :x, :at)
            on conflict (tenant_id, sku) do update set converted = inventory_backorder_stat.converted + :c,
              cancelled = inventory_backorder_stat.cancelled + :x, updated_at = :at
            """).param("t", t()).param("s", sku).param("c", "converted".equals(outcome) ? 1 : 0).param("x", "cancelled".equals(outcome) ? 1 : 0)
            .param("at", Timestamp.from(at)).update();
        return true;
    }

    public record BackorderStat(String sku, int converted, int cancelled) {}

    public Optional<BackorderStat> backorderStat(String sku) {
        return jdbc.sql("select sku, converted, cancelled from inventory_backorder_stat where tenant_id = :t and sku = :s")
            .param("t", t()).param("s", sku).query((rs, i) -> new BackorderStat(rs.getString(1), rs.getInt(2), rs.getInt(3))).optional();
    }

    static LocalDate day(Instant i) { return i.atZone(ZoneOffset.UTC).toLocalDate(); }
}
