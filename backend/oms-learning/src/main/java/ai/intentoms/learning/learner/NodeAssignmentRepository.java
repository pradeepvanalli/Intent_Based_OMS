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

/** order.sourced -&gt; node assignment, so a later delivery/shipment outcome can be attributed to the node that
 * actually fulfilled it, and compared against the ETA promised at sourcing time (FS-02, FS-03, CX-01 all need this
 * — neither order.shipped nor order.delivered carries promisedEta/onTime itself). */
@Repository
public class NodeAssignmentRepository {
    private final JdbcClient jdbc;
    public NodeAssignmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void record(String orderId, String nodeId, String skuClass, LocalDate promisedEta, Instant at) {
        jdbc.sql("""
            insert into node_assignment(tenant_id, order_id, node_id, sku_class, promised_eta, assigned_at) values (:t, :o, :n, :sc, :eta, :at)
            on conflict (tenant_id, order_id, node_id) do update set promised_eta = coalesce(node_assignment.promised_eta, excluded.promised_eta)
            """)
            .param("t", TenantContext.require()).param("o", orderId).param("n", nodeId).param("sc", skuClass == null ? "default" : skuClass)
            .param("eta", promisedEta == null ? null : Date.valueOf(promisedEta)).param("at", Timestamp.from(at)).update();
    }

    public record Assignment(String nodeId, LocalDate promisedEta, String carrier, Instant shippedAt) {}

    public List<Assignment> assignmentsFor(String orderId) {
        String t = TenantContext.require();
        return jdbc.sql("select node_id, promised_eta, carrier, shipped_at from node_assignment where tenant_id = :t and order_id = :o").param("t", t).param("o", orderId)
            .query().listOfRows().stream().map(NodeAssignmentRepository::hydrate).toList();
    }

    public List<String> nodesFor(String orderId) { return assignmentsFor(orderId).stream().map(Assignment::nodeId).toList(); }

    public void recordShipped(String orderId, String carrier, Instant shippedAt) {
        jdbc.sql("update node_assignment set carrier = coalesce(carrier, :c), shipped_at = coalesce(shipped_at, :at) where tenant_id = :t and order_id = :o")
            .param("c", carrier).param("at", Timestamp.from(shippedAt)).param("t", TenantContext.require()).param("o", orderId).update();
    }

    private static Assignment hydrate(Map<String, Object> r) {
        Object eta = r.get("promised_eta");
        Object shippedAt = r.get("shipped_at");
        return new Assignment((String) r.get("node_id"), eta == null ? null : ((Date) eta).toLocalDate(), (String) r.get("carrier"),
            shippedAt == null ? null : ((Timestamp) shippedAt).toInstant());
    }
}
