package ai.intentoms.orchestration.shipment;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@code shipment} table access, tenant scoped. */
@Repository
public class ShipmentRepository {
    private static final TypeReference<List<ShipmentRecord.Line>> LINES = new TypeReference<>() {};
    private static final String COLS = "id, order_id, fulfilment_group_id, node_id, carrier, service, tracking, status, lines::text as lines, shipped_at, delivered_at, eta";
    private static final RowMapper<ShipmentRecord> MAPPER = (rs, i) -> new ShipmentRecord(rs.getString("id"), rs.getString("order_id"), rs.getString("fulfilment_group_id"),
        rs.getString("node_id"), rs.getString("carrier"), rs.getString("service"), rs.getString("tracking"), rs.getString("status"),
        Json.read(rs.getString("lines"), LINES), instant(rs.getTimestamp("shipped_at")), instant(rs.getTimestamp("delivered_at")), instant(rs.getTimestamp("eta")));
    private final JdbcClient jdbc;

    public ShipmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public ShipmentRecord insert(ShipmentRecord s) {
        jdbc.sql("""
            insert into shipment(id, tenant_id, order_id, fulfilment_group_id, node_id, carrier, service, tracking, status, lines, shipped_at, delivered_at, eta)
            values (:id, :t, :o, :g, :n, :c, :s, :tr, :st, :lines, :sh, :d, :eta)
            """).param("id", s.id()).param("t", TenantContext.require()).param("o", s.orderId()).param("g", s.fulfilmentGroupId()).param("n", s.nodeId())
            .param("c", s.carrier()).param("s", s.service()).param("tr", s.tracking()).param("st", s.status()).param("lines", Jsonb.of(s.lines()))
            .param("sh", ts(s.shippedAt())).param("d", ts(s.deliveredAt())).param("eta", ts(s.eta())).update();
        return get(s.id());
    }

    public void updateStatus(String id, String status, Instant deliveredAt) {
        jdbc.sql("update shipment set status = :s, delivered_at = coalesce(:d, delivered_at), updated_at = now() where tenant_id = :t and id = :id")
            .param("s", status).param("d", ts(deliveredAt)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void updateTracking(String id, String tracking) {
        jdbc.sql("update shipment set tracking = :tr, updated_at = now() where tenant_id = :t and id = :id").param("tr", tracking).param("t", TenantContext.require()).param("id", id).update();
    }

    public Optional<ShipmentRecord> find(String id) {
        return jdbc.sql("select " + COLS + " from shipment where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public ShipmentRecord get(String id) { return find(id).orElseThrow(() -> new ai.intentoms.platform.error.NotFoundException("Shipment " + id + " not found")); }

    public List<ShipmentRecord> forOrder(String orderId) {
        return jdbc.sql("select " + COLS + " from shipment where tenant_id = :t and order_id = :o order by created_at").param("t", TenantContext.require()).param("o", orderId).query(MAPPER).list();
    }

    public List<ShipmentRecord> byTracking(String tracking) {
        return jdbc.sql("select " + COLS + " from shipment where tenant_id = :t and tracking = :tr order by created_at").param("t", TenantContext.require()).param("tr", tracking).query(MAPPER).list();
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
}
