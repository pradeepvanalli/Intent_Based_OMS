package ai.intentoms.orchestration.integration;

import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link WmsConnector}: records every release in {@code wms_release} and does nothing else. Shipments, short picks
 * and store rejects come back through {@code POST /webhooks/wms/{nodeId}} (scripts/demo.sh step 5). One row per fulfilment
 * group, so re-sending the same release on a workflow replay is a no-op.
 */
@Component
public class MockWmsConnector implements WmsConnector {
    private static final Logger log = LoggerFactory.getLogger(MockWmsConnector.class);
    private final JdbcClient jdbc;

    public MockWmsConnector(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Map<String, Object> sendRelease(Order order, FulfilmentGroup group) {
        String tenant = TenantContext.require();
        String id = Ids.prefixed("rel");
        List<Map<String, Object>> lines = group.allocations().stream().<Map<String, Object>>map(a -> Map.of("lineId", a.lineId(), "sku", a.sku(), "qty", a.qty())).toList();
        int inserted = jdbc.sql("""
            insert into wms_release(id, tenant_id, order_id, fulfilment_group_id, node_id, lines, status)
            values (:id, :t, :o, :g, :n, :lines, 'sent') on conflict (tenant_id, fulfilment_group_id) do nothing
            """).param("id", id).param("t", tenant).param("o", order.id()).param("g", group.id()).param("n", group.nodeId()).param("lines", Jsonb.of(lines)).update();
        String releaseId = inserted > 0 ? id : jdbc.sql("select id from wms_release where tenant_id = :t and fulfilment_group_id = :g").param("t", tenant).param("g", group.id()).query(String.class).single();
        log.info("Mock WMS release {} for order {} group {} at node {} ({} lines){}", releaseId, order.id(), group.id(), group.nodeId(), lines.size(), inserted > 0 ? "" : " [already sent]");
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("releaseId", releaseId);
        ack.put("nodeId", group.nodeId());
        ack.put("lines", lines.size());
        ack.put("replayed", inserted == 0);
        return ack;
    }

    @Override
    public Map<String, Object> cancelRelease(Order order, FulfilmentGroup group, String reason) {
        int n = jdbc.sql("update wms_release set status = 'cancelled', cancelled_at = now() where tenant_id = :t and fulfilment_group_id = :g and status <> 'cancelled'")
            .param("t", TenantContext.require()).param("g", group.id()).update();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("fulfilmentGroupId", group.id());
        ack.put("cancelled", n > 0);
        ack.put("reason", reason == null ? "" : reason);
        return ack;
    }

    /** Releases recorded for an order (for the order view and tests). */
    public List<Map<String, Object>> releasesFor(String orderId) {
        return jdbc.sql("select id, fulfilment_group_id, node_id, status, lines::text as lines, sent_at from wms_release where tenant_id = :t and order_id = :o order by sent_at")
            .param("t", TenantContext.require()).param("o", orderId).query().listOfRows();
    }
}
