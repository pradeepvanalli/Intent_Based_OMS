package ai.intentoms.integrations.notify;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** {@code notification_log}: every outbound customer message attempt (append-only). Recipients are stored tokenised. */
@Repository
public class NotificationLogRepository {
    public record Entry(String id, String orderId, String channel, String template, String recipient, String status, String provider, String providerRef,
                        String communicationId, String error, Instant at) {}

    private static final RowMapper<Entry> MAPPER = (rs, i) -> new Entry(rs.getString("id"), rs.getString("order_id"), rs.getString("channel"), rs.getString("template"),
        rs.getString("recipient"), rs.getString("status"), rs.getString("provider"), rs.getString("provider_ref"), rs.getString("communication_id"), rs.getString("error"),
        rs.getTimestamp("at").toInstant());
    private final JdbcClient jdbc;

    public NotificationLogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Entry insert(String orderId, String channel, String template, String recipientToken, String status, String provider, String providerRef, String communicationId, String error, Instant at) {
        String id = Ids.prefixed("ntf");
        jdbc.sql("""
            insert into notification_log(id, tenant_id, order_id, channel, template, recipient, status, provider, provider_ref, communication_id, error, at)
            values (:id, :t, :o, :c, :tpl, :r, :s, :p, :ref, :com, :e, :at)
            """).param("id", id).param("t", TenantContext.require()).param("o", orderId).param("c", channel).param("tpl", template).param("r", recipientToken)
            .param("s", status).param("p", provider).param("ref", providerRef).param("com", communicationId).param("e", error).param("at", Timestamp.from(at)).update();
        return new Entry(id, orderId, channel, template, recipientToken, status, provider, providerRef, communicationId, error, at);
    }

    public List<Entry> forOrder(String orderId) {
        return jdbc.sql("select * from notification_log where tenant_id = :t and order_id = :o order by at").param("t", TenantContext.require()).param("o", orderId).query(MAPPER).list();
    }

    public List<Entry> recent(int limit) {
        return jdbc.sql("select * from notification_log where tenant_id = :t order by at desc limit :l").param("t", TenantContext.require()).param("l", Math.max(1, Math.min(limit, 500))).query(MAPPER).list();
    }
}
