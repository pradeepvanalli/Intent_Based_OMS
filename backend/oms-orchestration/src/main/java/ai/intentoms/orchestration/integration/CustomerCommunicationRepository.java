package ai.intentoms.orchestration.integration;

import ai.intentoms.order.model.Communication;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** {@code customer_communication} rows (FR-CS-01). */
@Repository
public class CustomerCommunicationRepository {
    private static final RowMapper<Communication> MAPPER = (rs, i) -> new Communication(rs.getString("id"), rs.getTimestamp("at").toInstant(), rs.getString("channel"),
        rs.getString("direction"), rs.getString("subject"), rs.getString("summary"), rs.getString("sent_by"));
    private final JdbcClient jdbc;

    public CustomerCommunicationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String id, String orderId, String channel, String direction, String template, String subject, String summary, String by, Instant at) {
        jdbc.sql("insert into customer_communication(id, tenant_id, order_id, channel, direction, template, subject, summary, sent_by, at) values (:id, :t, :o, :c, :d, :tpl, :s, :sum, :by, :at)")
            .param("id", id).param("t", TenantContext.require()).param("o", orderId).param("c", channel).param("d", direction).param("tpl", template)
            .param("s", subject).param("sum", summary).param("by", by).param("at", Timestamp.from(at)).update();
    }

    public List<Communication> forOrder(String orderId) {
        return jdbc.sql("select id, at, channel, direction, subject, summary, sent_by from customer_communication where tenant_id = :t and order_id = :o order by at")
            .param("t", TenantContext.require()).param("o", orderId).query(MAPPER).list();
    }
}
