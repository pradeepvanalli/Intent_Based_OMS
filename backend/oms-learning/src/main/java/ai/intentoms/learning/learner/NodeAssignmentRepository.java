package ai.intentoms.learning.learner;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** order.sourced -&gt; node assignment, so a later delivery/shipment outcome can be attributed to the node that
 * actually fulfilled it, and compared against the ETA and on-time probability promised at sourcing time (FS-02,
 * FS-03, FS-05, CX-01). Besides the node, each row keeps what sourcing knew then: destination ZIP3, ship method, SKU
 * class, day part, the node's backlog, and later the carrier, ship time and whether delivery was on time. */
@Repository
public class NodeAssignmentRepository {
    private final JdbcClient jdbc;
    public NodeAssignmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * What sourcing knew about one package when it assigned the node. Any field may be null for older events.
     * {@code shipBy} is the date the package must leave the node (FS-02 on-time pick); {@code baseOnTime} is the on-time
     * probability before lane calibration, {@code statedOnTime} the one the order was given (CX-01).
     */
    public record Sourced(String skuClass, LocalDate promisedEta, String destZip3, String shipMethod, String dayPart, Integer backlogUnits, Double statedOnTime,
                          LocalDate shipBy, Double baseOnTime) {
        public Sourced(String skuClass, LocalDate promisedEta, String destZip3, String shipMethod, String dayPart, Integer backlogUnits, Double statedOnTime) {
            this(skuClass, promisedEta, destZip3, shipMethod, dayPart, backlogUnits, statedOnTime, null, null);
        }
        public static Sourced of(String skuClass, LocalDate promisedEta) { return new Sourced(skuClass, promisedEta, null, null, null, null, null); }
    }

    public void record(String orderId, String nodeId, String skuClass, LocalDate promisedEta, Instant at) {
        record(orderId, nodeId, Sourced.of(skuClass, promisedEta), at);
    }

    public void record(String orderId, String nodeId, Sourced s, Instant at) {
        jdbc.sql("""
            insert into node_assignment(tenant_id, order_id, node_id, sku_class, promised_eta, assigned_at, dest_zip3, ship_method, day_part, backlog_units, stated_on_time,
                                        ship_by, base_on_time)
            values (:t, :o, :n, :sc, :eta, :at, :zip3, :method, :dp, :backlog, :stated, :shipBy, :base)
            on conflict (tenant_id, order_id, node_id) do update set promised_eta = coalesce(node_assignment.promised_eta, excluded.promised_eta),
              dest_zip3 = coalesce(node_assignment.dest_zip3, excluded.dest_zip3), ship_method = coalesce(node_assignment.ship_method, excluded.ship_method),
              day_part = coalesce(node_assignment.day_part, excluded.day_part), backlog_units = coalesce(node_assignment.backlog_units, excluded.backlog_units),
              stated_on_time = coalesce(node_assignment.stated_on_time, excluded.stated_on_time), ship_by = coalesce(node_assignment.ship_by, excluded.ship_by),
              base_on_time = coalesce(node_assignment.base_on_time, excluded.base_on_time)
            """)
            .param("t", TenantContext.require()).param("o", orderId).param("n", nodeId).param("sc", s.skuClass() == null ? "default" : s.skuClass())
            .param("eta", s.promisedEta() == null ? null : Date.valueOf(s.promisedEta())).param("at", Timestamp.from(at))
            .param("zip3", s.destZip3()).param("method", s.shipMethod()).param("dp", s.dayPart()).param("backlog", s.backlogUnits()).param("stated", s.statedOnTime())
            .param("shipBy", s.shipBy() == null ? null : Date.valueOf(s.shipBy())).param("base", s.baseOnTime())
            .update();
    }

    public record Assignment(String nodeId, LocalDate promisedEta, String carrier, Instant shippedAt, String skuClass, String destZip3, String shipMethod,
                             String dayPart, Integer backlogUnits, Double statedOnTime, Instant deliveredAt, Boolean deliveredOnTime, LocalDate shipBy,
                             Boolean pickedOnTime, Double baseOnTime) {}

    private static final String COLS = "node_id, promised_eta, carrier, shipped_at, sku_class, dest_zip3, ship_method, day_part, backlog_units, stated_on_time, " +
        "delivered_at, delivered_on_time, ship_by, picked_on_time, base_on_time";

    public List<Assignment> assignmentsFor(String orderId) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from node_assignment where tenant_id = :t and order_id = :o").param("t", t).param("o", orderId)
            .query().listOfRows().stream().map(NodeAssignmentRepository::hydrate).toList();
    }

    public List<String> nodesFor(String orderId) { return assignmentsFor(orderId).stream().map(Assignment::nodeId).toList(); }

    public void recordShipped(String orderId, String carrier, Instant shippedAt) {
        jdbc.sql("update node_assignment set carrier = coalesce(carrier, :c), shipped_at = coalesce(shipped_at, :at) where tenant_id = :t and order_id = :o")
            .param("c", carrier).param("at", Timestamp.from(shippedAt)).param("t", TenantContext.require()).param("o", orderId).update();
    }

    /** The shipment of one node's package; falls back to every assignment of the order when the node is unknown. */
    public void recordShipped(String orderId, String nodeId, String carrier, Instant shippedAt) {
        if (nodeId == null) { recordShipped(orderId, carrier, shippedAt); return; }
        jdbc.sql("update node_assignment set carrier = coalesce(carrier, :c), shipped_at = coalesce(shipped_at, :at) where tenant_id = :t and order_id = :o and node_id = :n")
            .param("c", carrier).param("at", Timestamp.from(shippedAt)).param("t", TenantContext.require()).param("o", orderId).param("n", nodeId).update();
    }

    /** FS-02: records whether the node's pick left on time; the first shipment wins (a redelivered event changes nothing). */
    public boolean recordPicked(String orderId, String nodeId, boolean onTime) {
        return jdbc.sql("update node_assignment set picked_on_time = :ot where tenant_id = :t and order_id = :o and node_id = :n and picked_on_time is null")
            .param("ot", onTime).param("t", TenantContext.require()).param("o", orderId).param("n", nodeId).update() == 1;
    }

    /** Records the delivery outcome of one assignment; the first delivery wins (a redelivered event changes nothing). */
    public boolean recordDelivered(String orderId, String nodeId, Instant deliveredAt, boolean onTime) {
        return jdbc.sql("update node_assignment set delivered_at = :at, delivered_on_time = :ot where tenant_id = :t and order_id = :o and node_id = :n and delivered_at is null")
            .param("at", Timestamp.from(deliveredAt)).param("ot", onTime).param("t", TenantContext.require()).param("o", orderId).param("n", nodeId).update() == 1;
    }

    /** FS-05: (backlog at sourcing, delivered on time) for one node's deliveries since {@code since}. */
    public List<double[]> backlogOutcomes(String nodeId, Instant since) {
        return jdbc.sql("""
            select backlog_units, delivered_on_time from node_assignment
            where tenant_id = :t and node_id = :n and delivered_at >= :since and backlog_units is not null and delivered_on_time is not null
            """).param("t", TenantContext.require()).param("n", nodeId).param("since", Timestamp.from(since)).query().listOfRows().stream()
            .map(r -> new double[]{((Number) r.get("backlog_units")).doubleValue(), Boolean.TRUE.equals(r.get("delivered_on_time")) ? 0.0 : 1.0}).toList();
    }

    public List<String> nodesDeliveredSince(Instant since) {
        return jdbc.sql("select distinct node_id from node_assignment where tenant_id = :t and delivered_at >= :since")
            .param("t", TenantContext.require()).param("since", Timestamp.from(since)).query(String.class).list();
    }

    /** FS-03: the carrier this node most often ships this service with, learned from shipments. */
    public Optional<String> usualCarrier(String shipMethod, String nodeId) {
        return jdbc.sql("""
            select carrier from node_assignment where tenant_id = :t and ship_method = :m and node_id = :n and carrier is not null
            group by carrier order by count(*) desc, carrier limit 1
            """).param("t", TenantContext.require()).param("m", shipMethod).param("n", nodeId).query(String.class).optional();
    }

    private static Assignment hydrate(Map<String, Object> r) {
        Object eta = r.get("promised_eta");
        Object shipBy = r.get("ship_by");
        return new Assignment((String) r.get("node_id"), eta == null ? null : ((Date) eta).toLocalDate(), (String) r.get("carrier"), ts(r.get("shipped_at")),
            (String) r.get("sku_class"), (String) r.get("dest_zip3"), (String) r.get("ship_method"), (String) r.get("day_part"),
            r.get("backlog_units") == null ? null : ((Number) r.get("backlog_units")).intValue(), (Double) r.get("stated_on_time"), ts(r.get("delivered_at")),
            (Boolean) r.get("delivered_on_time"), shipBy == null ? null : ((Date) shipBy).toLocalDate(), (Boolean) r.get("picked_on_time"), (Double) r.get("base_on_time"));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
