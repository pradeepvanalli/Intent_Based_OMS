package ai.intentoms.orchestration.seed;

import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.returns.model.ReturnQuery;
import ai.intentoms.returns.service.ReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The full demo seed on an empty tenant: orders in several statuses, returns, exceptions; idempotent on a second run. */
class DemoSeederIntegrationTest extends OrchestrationTestBase {
    @Autowired ReturnService returns;

    @Test
    @SuppressWarnings("unchecked")
    void fullSeedProducesVariedOrdersAndIsIdempotent() {
        Map<String, Object> first = seeder.seedOrders();
        assertThat((Integer) first.get("captured")).isGreaterThanOrEqualTo(20);
        assertThat((List<String>) first.get("failed")).as("every sample captures and progresses").isEmpty();
        Map<String, Long> statuses = (Map<String, Long>) first.get("statusCounts");
        assertThat(statuses.keySet()).as("statuses " + statuses).hasSizeGreaterThanOrEqualTo(5);
        assertThat(statuses).containsKeys("released", "shipped", "delivered");
        assertThat((List<String>) first.get("returns")).hasSizeGreaterThanOrEqualTo(2);
        assertThat((List<String>) first.get("exceptions")).hasSize(2);
        long total = orders.search(OrderQuery.all(0, 1)).total();
        assertThat(returns.list(new ReturnQuery(null, null, null, null, null, 0, 50)).total()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("select count(*) from shipment where tenant_id = :t").param("t", tenant).query(Long.class).single()).isGreaterThanOrEqualTo(8L);

        Map<String, Object> second = seeder.seedOrders();
        assertThat(second).containsKey("skipped");
        assertThat(orders.search(OrderQuery.all(0, 1)).total()).isEqualTo(total);
        Map<String, Object> cfg = seeder.seedConfig();
        assertThat(cfg).containsEntry("stateModels", 0).containsEntry("decisions", 0).containsEntry("tenantConfigKeysWritten", 0);
        assertThat(seeder.seedInventory()).containsEntry("positionsWritten", 0);
    }
}
