package ai.intentoms.platform.ledger;

import ai.intentoms.platform.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Drains the outbox in order and delivers each event to in-process subscribers and external publishers.
 * Uses FOR UPDATE SKIP LOCKED so several app instances share the work; a failing event is retried with backoff and
 * parked after maxAttempts (visible in /api/v1/admin/outbox) without blocking other aggregates.
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectProvider<EventSubscriber> subscribers;
    private final ObjectProvider<EventPublisher> publishers;
    private final OutboxProperties props;
    private final MeterRegistry metrics;

    @ConfigurationProperties(prefix = "oms.outbox")
    public record OutboxProperties(int batchSize, int maxAttempts, boolean enabled) {
        public OutboxProperties {
            if (batchSize <= 0) batchSize = 200;
            if (maxAttempts <= 0) maxAttempts = 12;
        }
    }

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, ObjectProvider<EventSubscriber> subscribers, ObjectProvider<EventPublisher> publishers, OutboxProperties props, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.subscribers = subscribers;
        this.publishers = publishers;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${oms.outbox.poll-ms:250}")
    public void drain() {
        if (!props.enabled()) return;
        int processed;
        do { processed = drainBatch(); } while (processed >= props.batchSize());
    }

    public int drainBatch() {
        Integer n = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.sql("""
                select o.position, o.event_id, o.tenant_id, o.attempts from outbox o
                where o.published_at is null and o.next_attempt_at <= now() and o.attempts < :max
                order by o.position limit :n for update skip locked
                """).param("max", props.maxAttempts()).param("n", props.batchSize()).query().listOfRows();
            for (Map<String, Object> row : rows) {
                String eventId = (String) row.get("event_id");
                String tenant = (String) row.get("tenant_id");
                int attempts = ((Number) row.get("attempts")).intValue();
                try {
                    Event e = TenantContext.supplyAs(tenant, () -> jdbc.sql("select * from event where tenant_id = :t and event_id = :id")
                        .param("t", tenant).param("id", eventId).query(JdbcEventStore.MAPPER).single());
                    TenantContext.runAs(tenant, () -> deliver(e));
                    jdbc.sql("update outbox set published_at = now(), attempts = attempts + 1 where event_id = :id").param("id", eventId).update();
                    metrics.counter("oms.outbox.delivered").increment();
                } catch (Exception ex) {
                    long backoffSec = Math.min(3600, (long) Math.pow(2, Math.min(attempts, 12)));
                    log.warn("Outbox delivery failed for {} (attempt {}): {}", eventId, attempts + 1, ex.toString());
                    jdbc.sql("update outbox set attempts = attempts + 1, last_error = :err, next_attempt_at = now() + make_interval(secs => :b) where event_id = :id")
                        .param("err", abbreviate(ex.toString())).param("b", (double) backoffSec).param("id", eventId).update();
                    metrics.counter("oms.outbox.failed").increment();
                }
            }
            return rows.size();
        });
        return n == null ? 0 : n;
    }

    private void deliver(Event e) {
        for (EventSubscriber s : subscribers) {
            if (matches(s.eventTypePrefixes(), e.type())) s.on(e);
        }
        for (EventPublisher p : publishers) p.publish(e);
    }

    static boolean matches(List<String> prefixes, String type) {
        if (prefixes == null || prefixes.isEmpty()) return false;
        for (String p : prefixes) if (p.equals("*") || type.startsWith(p)) return true;
        return false;
    }

    private static String abbreviate(String s) { return s.length() > 1000 ? s.substring(0, 1000) : s; }
}
