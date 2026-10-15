package ai.intentoms.integrations.marketplace;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** {@code marketplace_import}: one row per inbound marketplace order (webhook or poll) with the OMS order it became. */
@Repository
public class MarketplaceImportRepository {
    private final JdbcClient jdbc;

    public MarketplaceImportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public String record(String marketplace, String externalOrderId, String orderId, String status, String error, String payloadHash, Instant at) {
        String id = Ids.prefixed("mpi");
        jdbc.sql("insert into marketplace_import(id, tenant_id, marketplace, external_order_id, order_id, status, error, payload_hash, received_at) values (:id, :t, :m, :x, :o, :s, :e, :h, :at)")
            .param("id", id).param("t", TenantContext.require()).param("m", marketplace).param("x", externalOrderId).param("o", orderId).param("s", status)
            .param("e", error).param("h", payloadHash).param("at", Timestamp.from(at)).update();
        return id;
    }

    public List<Map<String, Object>> forExternal(String marketplace, String externalOrderId) {
        return jdbc.sql("select id, marketplace, external_order_id, order_id, status, error, received_at from marketplace_import where tenant_id = :t and marketplace = :m and external_order_id = :x order by received_at")
            .param("t", TenantContext.require()).param("m", marketplace).param("x", externalOrderId).query().listOfRows();
    }
}
