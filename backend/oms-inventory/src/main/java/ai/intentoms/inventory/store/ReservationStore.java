package ai.intentoms.inventory.store;

import ai.intentoms.inventory.model.Reservation;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SQL for {@code inventory_reservation} (FR-INV-02). */
@Repository
public class ReservationStore {
    /** Full row including the order id, which the public {@link Reservation} record does not carry. */
    public record Row(Reservation reservation, String orderId) {}

    private static final RowMapper<Row> ROW = (rs, i) -> {
        Timestamp exp = rs.getTimestamp("expires_at");
        return new Row(new Reservation(rs.getString("id"), rs.getString("sku"), rs.getString("node_id"), rs.getInt("qty"), rs.getString("source"),
            rs.getString("ref_id"), exp == null ? null : exp.toInstant(), rs.getString("status")), rs.getString("order_id"));
    };
    private static final String COLS = "id, sku, node_id, qty, source, ref_id, order_id, expires_at, status";

    private final JdbcClient jdbc;

    public ReservationStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String tenant, Reservation r, String orderId, Instant now) {
        jdbc.sql("""
            insert into inventory_reservation(id, tenant_id, sku, node_id, qty, source, ref_id, order_id, expires_at, status, created_at, updated_at)
            values (:id, :t, :s, :n, :q, :src, :ref, :order, :exp, :st, :now, :now)
            """).param("id", r.id()).param("t", tenant).param("s", r.sku()).param("n", r.nodeId()).param("q", r.qty()).param("src", r.source())
            .param("ref", r.refId()).param("order", orderId).param("exp", r.expiresAt() == null ? null : Timestamp.from(r.expiresAt()))
            .param("st", r.status()).param("now", Timestamp.from(now)).update();
    }

    public Optional<Row> find(String tenant, String id) {
        return jdbc.sql("select " + COLS + " from inventory_reservation where tenant_id = :t and id = :id").param("t", tenant).param("id", id).query(ROW).optional();
    }

    /** Locks the reservation row for the current transaction so lifecycle transitions are serialised. */
    public Optional<Row> lock(String tenant, String id) {
        return jdbc.sql("select " + COLS + " from inventory_reservation where tenant_id = :t and id = :id for update").param("t", tenant).param("id", id).query(ROW).optional();
    }

    /** Reservations whose {@code ref_id} or {@code order_id} equals {@code ref}, oldest first. */
    public List<Row> forRef(String tenant, String ref) {
        return jdbc.sql("select " + COLS + " from inventory_reservation where tenant_id = :t and (ref_id = :r or order_id = :r) order by created_at, id")
            .param("t", tenant).param("r", ref).query(ROW).list();
    }

    public void convert(String tenant, String id, String orderId, Instant now) {
        jdbc.sql("update inventory_reservation set source = 'order', order_id = :o, expires_at = null, updated_at = :now where tenant_id = :t and id = :id")
            .param("o", orderId).param("now", Timestamp.from(now)).param("t", tenant).param("id", id).update();
    }

    public void setStatus(String tenant, String id, String status, Instant now) {
        jdbc.sql("update inventory_reservation set status = :st, updated_at = :now where tenant_id = :t and id = :id")
            .param("st", status).param("now", Timestamp.from(now)).param("t", tenant).param("id", id).update();
    }

    /** Marks every ACTIVE reservation with {@code expires_at < now} EXPIRED and returns the affected rows. */
    public List<Row> expireDue(String tenant, Instant now) {
        return jdbc.sql("update inventory_reservation set status = 'EXPIRED', updated_at = :now where tenant_id = :t and status = 'ACTIVE' and expires_at < :now returning " + COLS)
            .param("now", Timestamp.from(now)).param("t", tenant).query(ROW).list();
    }
}
