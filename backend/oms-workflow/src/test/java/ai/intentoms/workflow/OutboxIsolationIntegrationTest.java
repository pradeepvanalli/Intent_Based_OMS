package ai.intentoms.workflow;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.ledger.OutboxRelay;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One failing subscriber must never stall event delivery: other events (of any tenant) keep flowing, the healthy
 * subscribers of the failing event get it exactly once, only the failing subscriber is retried, and after the attempt
 * limit the event is dead-lettered instead of retried forever. Covers the case that froze the outbox in the audit: a
 * subscriber that catches an exception thrown inside a nested transaction, leaving its transaction rollback-only.
 */
@Import(OutboxIsolationIntegrationTest.Subscribers.class)
@TestPropertySource(properties = "oms.outbox.max-attempts=3")
class OutboxIsolationIntegrationTest extends WorkflowTestBase {
    static final List<String> recorded = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Subscribers {
        /** Sees every test event. */
        @Bean EventSubscriber recordingSubscriber() {
            return new EventSubscriber() {
                @Override public List<String> eventTypePrefixes() { return List.of("test."); }
                @Override public void on(Event e) { recorded.add(e.type() + ":" + e.aggregateId()); }
            };
        }

        /** Throws outright on test.poison. */
        @Bean EventSubscriber throwingSubscriber() { return new ThrowingSubscriber(); }

        /** Catches an exception from a nested transaction (the rollback-only trap) on test.rollback_only. */
        @Bean EventSubscriber rollbackOnlySubscriber(PlatformTransactionManager tm) { return new RollbackOnlySubscriber(new TransactionTemplate(tm)); }
    }

    static class ThrowingSubscriber implements EventSubscriber {
        @Override public List<String> eventTypePrefixes() { return List.of("test.poison"); }
        @Override public void on(Event e) { throw new IllegalStateException("poison"); }
    }

    static class RollbackOnlySubscriber implements EventSubscriber {
        private final TransactionTemplate nested;
        RollbackOnlySubscriber(TransactionTemplate nested) { this.nested = nested; }
        @Override public List<String> eventTypePrefixes() { return List.of("test.rollback_only"); }
        @Override public void on(Event e) {
            try { nested.executeWithoutResult(s -> { throw new IllegalArgumentException("not found"); }); }
            catch (IllegalArgumentException caught) { /* caught, but the shared transaction is now rollback-only */ }
        }
    }

    @Autowired EventStore events;
    @Autowired OutboxRelay relay;

    private String append(String tenant, String type, String aggregateId) {
        return TenantContext.supplyAs(tenant, () -> events.append("test", aggregateId, new NewEvent(type, Map.of("n", 1), Actor.system("test"))).id());
    }

    private Map<String, Object> row(String eventId) {
        return jdbc.sql("select attempts, published_at, dead_lettered_at, pending_subscribers::text as pending from outbox where event_id = :id")
            .param("id", eventId).query().singleRow();
    }

    private void drainAll() { int guard = 0; while (relay.drainBatch() > 0 && guard++ < 20) { /* keep draining */ } }

    @Test
    void aFailingSubscriberNeitherBlocksOtherEventsNorRetriesHealthySubscribers() {
        jdbc.sql("insert into tenant(tenant_id, name) values ('outbox-b', 'outbox-b') on conflict do nothing").update();
        jdbc.sql("update outbox set published_at = now() where published_at is null").update(); // start from a clean queue
        recorded.clear();
        String poison = append(TENANT, "test.poison", "p1");
        String trap = append(TENANT, "test.rollback_only", "r1");
        String healthy = append("outbox-b", "test.ok", "b1");

        drainAll();

        assertThat(row(healthy).get("published_at")).isNotNull();
        assertThat(recorded).contains("test.ok:b1", "test.poison:p1", "test.rollback_only:r1");
        for (String id : List.of(poison, trap)) {
            Map<String, Object> r = row(id);
            assertThat(r.get("published_at")).isNull();
            assertThat(((Number) r.get("attempts")).intValue()).isEqualTo(1);
            assertThat(String.valueOf(r.get("pending"))).doesNotContain("recording").contains("Subscriber");
        }

        // Retry until the limit: only the failing subscriber is called again, then the event is dead-lettered.
        for (int i = 0; i < 4; i++) {
            jdbc.sql("update outbox set next_attempt_at = now() where event_id in (:ids)").param("ids", List.of(poison, trap)).update();
            drainAll();
        }
        assertThat(recorded.stream().filter(x -> x.equals("test.poison:p1")).count()).as("healthy subscriber saw the poison event once").isEqualTo(1);
        for (String id : List.of(poison, trap)) {
            Map<String, Object> r = row(id);
            assertThat(((Number) r.get("attempts")).intValue()).isEqualTo(3);
            assertThat(r.get("dead_lettered_at")).isNotNull();
        }

        // A dead letter never comes back, and new events still flow.
        String later = append(TENANT, "test.ok", "after");
        jdbc.sql("update outbox set next_attempt_at = now() where event_id in (:ids)").param("ids", List.of(poison, trap)).update();
        drainAll();
        assertThat(row(later).get("published_at")).isNotNull();
        assertThat(((Number) row(poison).get("attempts")).intValue()).isEqualTo(3);
    }
}
