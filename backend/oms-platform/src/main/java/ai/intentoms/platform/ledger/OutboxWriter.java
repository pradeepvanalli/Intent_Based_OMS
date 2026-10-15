package ai.intentoms.platform.ledger;

import ai.intentoms.platform.jdbc.Jsonb;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Transactional outbox: every ledger event is also queued for delivery in the same transaction. */
@Component
public class OutboxWriter {
    private final JdbcClient jdbc;

    public OutboxWriter(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void enqueue(Event e) {
        jdbc.sql("insert into outbox(event_id, tenant_id, aggregate_type, aggregate_id, type, cloud_event) values (:id, :t, :at, :aid, :type, :ce)")
            .param("id", e.id()).param("t", e.tenantId()).param("at", e.aggregateType()).param("aid", e.aggregateId())
            .param("type", e.type()).param("ce", Jsonb.of(e.toCloudEvent())).update();
    }
}
