package ai.intentoms.monitoring.exception;

import ai.intentoms.inventory.learning.BackorderOutcomeLearner;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** IU-06 with the order module present: backorder resolutions per SKU, and the backorder decision seeing stock elsewhere. */
class BackorderLearningIntegrationTest extends MonitoringTestBase {
    @Autowired BackorderOutcomeLearner learner;
    @Autowired MonitoringEventSubscriber subscriber;
    @Autowired InventoryService inventory;
    @Autowired LearnedFactRepository facts;
    @Autowired JdbcClient jdbc;

    private Event transition(Order o, String to) {
        return new Event(Ids.id(), TenantContext.require(), "order", o.id(), 1, "order.transitioned",
            Map.of("entityType", "order_line", "entityId", o.id() + ":L1", "from", "backordered", "to", to), SYSTEM, null, o.id(), Map.of(), Instant.now());
    }

    private Map<String, Object> stat() {
        return jdbc.sql("select converted, cancelled from inventory_backorder_stat where tenant_id = :t and sku = 'SKU-1'")
            .param("t", TenantContext.require()).query().singleRow();
    }

    @Test
    void backorderResolutionsAreCountedOncePerEventAndBecomeAPerSkuRate() {
        jdbc.sql("delete from inventory_backorder_stat where tenant_id = :t and sku = 'SKU-1'").param("t", TenantContext.require()).update();
        Order kept = newOrder("web", "sourcing", false);
        Order lost = newOrder("web", "sourcing", false);
        Event keptEvent = transition(kept, "awaiting_sourcing");
        learner.on(keptEvent);
        learner.on(keptEvent);   // redelivery
        learner.on(transition(lost, "cancelled"));

        assertThat(stat()).containsEntry("converted", 1).containsEntry("cancelled", 1);
        var rate = facts.active(SubjectType.SKU, "SKU-1", FactKey.BACKORDER_CONVERSION_RATE).orElseThrow();
        assertThat(((TypedValue.NumberValue) rate.value()).value()).isCloseTo((1 + 0.5 * 5) / (2 + 5.0), within(1e-9));
        assertThat(rate.sampleSize()).isEqualTo(2);
    }

    @Test
    void backorderDecisionSeesAvailabilityAtOtherNodes() {
        String sku = "BO-" + Ids.shortId();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "DC-EAST", 5), InventoryPosition.onHand(sku, "DC-WEST", 7), InventoryPosition.onHand(sku, "STORE-9", 0)), "wms");
        assertThat(subscriber.atpElsewhere(sku, "DC-EAST")).isEqualTo(7);
        assertThat(subscriber.atpElsewhere(sku, null)).isEqualTo(12);
        assertThat(subscriber.atpElsewhere(null, "DC-EAST")).isZero();
    }
}
