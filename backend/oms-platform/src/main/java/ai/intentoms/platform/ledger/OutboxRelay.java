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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Drains the outbox in order and delivers each event to in-process subscribers and external publishers.
 *
 * <p>Isolation: a batch is claimed with {@code FOR UPDATE SKIP LOCKED} and a lease (so several app instances share
 * the work), then every subscriber and publisher receives the event in its <em>own</em> transaction. A subscriber that
 * throws, or whose transaction was marked rollback-only, fails alone: the event still counts as delivered for everyone
 * else, the failed subscribers are recorded in {@code pending_subscribers}, and only they are retried with backoff.
 * After {@code maxAttempts} the event is dead-lettered ({@code dead_lettered_at}, metric {@code oms.outbox.dead_lettered},
 * an error log, listed in {@code /api/v1/admin/outbox}) and never blocks anything again.
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final String PUBLISHER_PREFIX = "publisher:";

    private final JdbcClient jdbc;
    private final TransactionTemplate isolated;
    private final ObjectProvider<EventSubscriber> subscribers;
    private final ObjectProvider<EventPublisher> publishers;
    private final OutboxProperties props;
    private final MeterRegistry metrics;

    @ConfigurationProperties(prefix = "oms.outbox")
    public record OutboxProperties(int batchSize, int maxAttempts, boolean enabled, int leaseSeconds) {
        public OutboxProperties {
            if (batchSize <= 0) batchSize = 200;
            if (maxAttempts <= 0) maxAttempts = 10;
            if (leaseSeconds <= 0) leaseSeconds = 300;
        }
    }

    public OutboxRelay(JdbcClient jdbc, PlatformTransactionManager txManager, ObjectProvider<EventSubscriber> subscribers,
                       ObjectProvider<EventPublisher> publishers, OutboxProperties props, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.isolated = new TransactionTemplate(txManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    private record Claimed(long position, String eventId, String tenantId, int attempts, Set<String> pending) {}

    /** Claims up to one batch, delivers it event by event, and returns how many events were claimed. */
    public int drainBatch() {
        List<Claimed> batch = claim();
        for (Claimed c : batch) deliverOne(c);
        return batch.size();
    }

    private List<Claimed> claim() {
        List<Claimed> rows = isolated.execute(status -> new ArrayList<>(jdbc.sql("""
            with c as (
              select position from outbox
              where published_at is null and dead_lettered_at is null and next_attempt_at <= now()
              order by position limit :n for update skip locked
            )
            update outbox o set next_attempt_at = now() + make_interval(secs => :lease)
            from c where o.position = c.position
            returning o.position, o.event_id, o.tenant_id, o.attempts, o.pending_subscribers
            """).param("n", props.batchSize()).param("lease", (double) props.leaseSeconds())
            .query((rs, i) -> new Claimed(rs.getLong("position"), rs.getString("event_id"), rs.getString("tenant_id"), rs.getInt("attempts"),
                toSet(rs.getArray("pending_subscribers")))).list()));
        if (rows == null) return List.of();
        rows.sort(Comparator.comparingLong(Claimed::position));
        return rows;
    }

    private void deliverOne(Claimed c) {
        Event e;
        try {
            e = TenantContext.supplyAs(c.tenantId(), () -> jdbc.sql("select * from event where tenant_id = :t and event_id = :id")
                .param("t", c.tenantId()).param("id", c.eventId()).query(JdbcEventStore.MAPPER).single());
        } catch (RuntimeException ex) {
            recordFailure(c, Set.of("event-load"), ex);
            return;
        }
        Set<String> failed = new LinkedHashSet<>();
        Exception firstError = null;
        for (EventSubscriber s : subscribers) {
            if (!matches(s.eventTypePrefixes(), e.type())) continue;
            String id = idOf(s);
            if (c.pending() != null && !c.pending().contains(id)) continue;
            try {
                isolated.executeWithoutResult(st -> TenantContext.runAs(c.tenantId(), () -> s.on(e)));
            } catch (Exception ex) {
                failed.add(id);
                if (firstError == null) firstError = ex;
                metrics.counter("oms.outbox.subscriber_failed", "subscriber", ClassUtils.getShortName(id)).increment();
                log.warn("Subscriber {} failed on event {} ({}): {}", ClassUtils.getShortName(id), e.id(), e.type(), ex.toString());
            }
        }
        for (EventPublisher p : publishers) {
            String id = PUBLISHER_PREFIX + ClassUtils.getUserClass(p).getName();
            if (c.pending() != null && !c.pending().contains(id)) continue;
            try {
                TenantContext.runAs(c.tenantId(), () -> p.publish(e));
            } catch (Exception ex) {
                failed.add(id);
                if (firstError == null) firstError = ex;
                log.warn("Publisher {} failed on event {}: {}", ClassUtils.getShortName(id), e.id(), ex.toString());
            }
        }
        if (failed.isEmpty()) {
            isolated.executeWithoutResult(st -> jdbc.sql("""
                update outbox set published_at = now(), attempts = attempts + 1, pending_subscribers = null, last_error = null where event_id = :id
                """).param("id", c.eventId()).update());
            metrics.counter("oms.outbox.delivered").increment();
        } else {
            recordFailure(c, failed, firstError);
        }
    }

    private void recordFailure(Claimed c, Set<String> failed, Exception ex) {
        int attempts = c.attempts() + 1;
        boolean dead = attempts >= props.maxAttempts();
        long backoffSec = Math.min(3600, (long) Math.pow(2, Math.min(attempts, 12)));
        String err = abbreviate(String.join(",", failed) + ": " + (ex == null ? "unknown" : ex.toString()));
        isolated.executeWithoutResult(st -> jdbc.sql("""
            update outbox set attempts = :a, last_error = :err, pending_subscribers = :pending,
              next_attempt_at = now() + make_interval(secs => :b), dead_lettered_at = case when :dead then now() else null end
            where event_id = :id
            """).param("a", attempts).param("err", err).param("pending", failed.toArray(String[]::new)).param("b", (double) backoffSec)
            .param("dead", dead).param("id", c.eventId()).update());
        metrics.counter("oms.outbox.failed").increment();
        if (dead) {
            metrics.counter("oms.outbox.dead_lettered").increment();
            log.error("Outbox event {} (tenant {}) dead-lettered after {} attempts; failing: {}", c.eventId(), c.tenantId(), attempts, err);
        }
    }

    static String idOf(EventSubscriber s) { return ClassUtils.getUserClass(s).getName(); }

    static boolean matches(List<String> prefixes, String type) {
        if (prefixes == null || prefixes.isEmpty()) return false;
        for (String p : prefixes) if (p.equals("*") || type.startsWith(p)) return true;
        return false;
    }

    private static Set<String> toSet(Array a) throws SQLException {
        if (a == null) return null;
        Object raw = a.getArray();
        if (!(raw instanceof Object[] arr)) return null;
        Set<String> s = new LinkedHashSet<>();
        for (Object o : arr) if (o != null) s.add(String.valueOf(o));
        return s;
    }

    private static String abbreviate(String s) { return s.length() > 1000 ? s.substring(0, 1000) : s; }
}
