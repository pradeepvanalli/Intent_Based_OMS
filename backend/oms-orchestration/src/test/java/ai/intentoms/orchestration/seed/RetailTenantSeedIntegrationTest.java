package ai.intentoms.orchestration.seed;

import ai.intentoms.monitoring.exception.ExceptionQuery;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.returns.model.ReturnQuery;
import ai.intentoms.returns.service.ReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds the {@code retail} reference tenant ("Maple & Main General Store") from {@code config/tenants/retail} exactly as the
 * start-up seeder does for every id in {@code oms.demo.tenants}: tenant row + metadata, dev users, config (metadata keys
 * skipped), {@code inventory.yaml}, {@code sample-orders.yaml} with its lifecycle, returns and exceptions; then checks the
 * second run is a no-op. Also validates the tenant folder: a bad sku / node / order type in the YAML fails this test.
 */
class RetailTenantSeedIntegrationTest extends OrchestrationTestBase {
    @Autowired ReturnService returns;
    @Autowired ExceptionService exceptions;

    @Test
    @SuppressWarnings("unchecked")
    void seedsMapleAndMainFromItsFolder() {
        Path dir = seeder.tenantDir("retail").orElseThrow();
        TenantMetadata meta = TenantMetadata.read(dir, "retail");
        assertThat(meta.name()).isEqualTo("Maple & Main General Store");
        assertThat(meta.vertical()).isEqualTo("General retail");
        assertThat(meta.description()).contains("two DCs");

        Map<String, Object> summary = seeder.seedTenant("retail");
        assertThat(summary).containsEntry("name", "Maple & Main General Store").containsEntry("vertical", "General retail");
        assertThat((Integer) summary.get("usersCreated")).as("admin, ops, cs, risk, finance, strategy").isEqualTo(6);
        assertThat(jdbc.sql("select user_id from app_user where tenant_id = 'retail' order by user_id").query(String.class).list())
            .containsExactly("admin", "cs", "finance", "ops", "risk", "strategy");
        assertThat(jdbc.sql("select name from tenant where tenant_id = 'retail'").query(String.class).single()).isEqualTo("Maple & Main General Store");

        Map<String, Object> config = (Map<String, Object>) summary.get("config");
        assertThat((Integer) config.get("decisions")).as("14 starter decisions + store_reject_reroute + curbside_eta_promise + payment_risk + rto_risk (SOO FD-01/FD-02)").isEqualTo(18);
        assertThat((Integer) config.get("nodes")).isEqualTo(9);
        assertThat((Integer) config.get("catalogItems")).isEqualTo(41);
        assertThat((Integer) config.get("orderTypes")).isEqualTo(7);
        assertThat((Integer) config.get("goldenCases")).isGreaterThanOrEqualTo(24);
        Map<String, Object> inventory = (Map<String, Object>) summary.get("inventory");
        assertThat(inventory).containsEntry("source", "inventory.yaml");
        assertThat((Integer) inventory.get("positionsWritten")).isEqualTo(160);

        Map<String, Object> orders = (Map<String, Object>) summary.get("orders");
        assertThat(orders).containsEntry("source", "sample-orders.yaml");
        assertThat((Integer) orders.get("captured")).isGreaterThanOrEqualTo(30);
        assertThat((List<String>) orders.get("failed")).as("every sample captures and progresses").isEmpty();
        assertThat((List<String>) orders.get("returns")).as("instant refund, out of window, flagged").hasSize(3);
        assertThat((List<String>) orders.get("exceptions")).as("address + carrier delay").hasSize(2);
        Map<String, Long> statuses = (Map<String, Long>) orders.get("statusCounts");
        assertThat(statuses).as("statuses " + statuses).containsKeys("released", "shipped", "delivered", "picked_up");
        assertThat(statuses.keySet()).hasSizeGreaterThanOrEqualTo(5);

        tenant = "retail";
        bind();
        assertThat(this.orders.search(OrderQuery.all(0, 1)).total()).isGreaterThanOrEqualTo(30);
        assertThat(returns.list(new ReturnQuery(null, null, null, null, null, 0, 50)).total()).isEqualTo(3);
        assertThat(exceptions.list(new ExceptionQuery(null, null, null, null, null, null, 0, 100)).total()).as("two seeded plus the store rejects raised by the workflows").isGreaterThanOrEqualTo(2);
        assertThat(config()).as("metadata keys are not runtime settings").doesNotContainKeys("name", "vertical", "description");
        assertThat(config()).containsKey("sourcing.margin_floor").containsKey("returns.keep_it_max_value_usd");
        assertThat(jdbc.sql("select count(*) from orders where tenant_id = 'retail' and channel = 'marketplace'").query(Long.class).single()).isGreaterThanOrEqualTo(4L);

        Map<String, Object> second = seeder.seedTenant("retail");
        assertThat((Map<String, Object>) second.get("orders")).containsKey("skipped");
        assertThat((Map<String, Object>) second.get("inventory")).containsEntry("positionsWritten", 0);
        assertThat((Integer) second.get("usersCreated")).isZero();
    }

    private Map<String, Object> config() { return this.config.all(""); }

    @Test
    void missingTenantFolderIsSkippedNotFailed() {
        assertThat(seeder.seedTenant("no-such-tenant-folder")).containsEntry("skipped", "config_dir_missing");
    }
}
