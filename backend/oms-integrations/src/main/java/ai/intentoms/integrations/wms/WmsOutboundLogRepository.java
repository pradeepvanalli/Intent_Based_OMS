package ai.intentoms.integrations.wms;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** {@code wms_outbound_log}: one append-only row per HTTP attempt of a release / cancel document. */
@Repository
public class WmsOutboundLogRepository {
    public record Entry(String id, String orderId, String fulfilmentGroupId, String nodeId, String documentType, String documentId, String url, int attempt, String status,
                        Integer httpStatus, String response, String error, Instant at) {}

    private static final RowMapper<Entry> MAPPER = (rs, i) -> new Entry(rs.getString("id"), rs.getString("order_id"), rs.getString("fulfilment_group_id"), rs.getString("node_id"),
        rs.getString("document_type"), rs.getString("document_id"), rs.getString("url"), rs.getInt("attempt"), rs.getString("status"),
        rs.getObject("http_status") == null ? null : rs.getInt("http_status"), rs.getString("response"), rs.getString("error"), rs.getTimestamp("at").toInstant());
    private final JdbcClient jdbc;

    public WmsOutboundLogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String orderId, String groupId, String nodeId, String documentType, String documentId, String url, int attempt, String status, Integer httpStatus, String response, String error, Instant at) {
        jdbc.sql("""
            insert into wms_outbound_log(id, tenant_id, order_id, fulfilment_group_id, node_id, document_type, document_id, url, attempt, status, http_status, response, error, at)
            values (:id, :t, :o, :g, :n, :dt, :d, :u, :a, :s, :hs, :r, :e, :at)
            """).param("id", Ids.prefixed("wmo")).param("t", TenantContext.require()).param("o", orderId).param("g", groupId).param("n", nodeId).param("dt", documentType)
            .param("d", documentId).param("u", url).param("a", attempt).param("s", status).param("hs", httpStatus).param("r", response).param("e", error).param("at", Timestamp.from(at)).update();
    }

    public List<Entry> forOrder(String orderId) {
        return jdbc.sql("select * from wms_outbound_log where tenant_id = :t and order_id = :o order by at").param("t", TenantContext.require()).param("o", orderId).query(MAPPER).list();
    }

    public List<Entry> forGroup(String groupId, String documentType) {
        return jdbc.sql("select * from wms_outbound_log where tenant_id = :t and fulfilment_group_id = :g and document_type = :d order by at")
            .param("t", TenantContext.require()).param("g", groupId).param("d", documentType).query(MAPPER).list();
    }
}
