package ai.intentoms.inventory.service;

import ai.intentoms.inventory.config.InventoryConfigKeys;
import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.model.ReconciliationReport;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-INV-01, FR-INV-03, FR-INV-04 against PostgreSQL. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class InventoryServiceTest extends PostgresTestBase {
    @Autowired InventoryService inventory;
    @Autowired InventoryConfidenceUpdater confidence;
    @Autowired TenantConfigStore config;
    @Autowired EventStore events;

    @AfterEach
    void resetConfig() {
        config.delete(InventoryConfigKeys.INCLUDE_INBOUND);
        config.delete(InventoryConfigKeys.CHANNEL_BUFFER);
        config.delete(InventoryConfigKeys.DRIFT_TOLERANCE);
    }

    private static String sku() { return "sku-" + UUID.randomUUID().toString().substring(0, 8); }
    private static String node() { return "node-" + UUID.randomUUID().toString().substring(0, 6); }

    @Test
    void upsertThenAtpAppliesSupplyAndDemandTypes() {
        String sku = sku(), dc = node(), store = node();
        inventory.upsert(List.of(
            new InventoryPosition(sku, dc, "on_hand", null, 100, 0, null),
            new InventoryPosition(sku, dc, "po", null, 40, 0, null),
            new InventoryPosition(sku, dc, "on_hand", "safety", 15, 0, null),
            new InventoryPosition(sku, dc, "on_hand", "allocated", 5, 0, null),
            new InventoryPosition(sku, store, "on_hand", null, 3, 0.8, null)), "wms");

        List<Atp> atp = inventory.atp(sku);
        assertThat(atp).hasSize(2);
        Atp atDc = inventory.atp(sku, dc);
        assertThat(atDc.onHand()).isEqualTo(100);
        assertThat(atDc.inbound()).isEqualTo(40);
        assertThat(atDc.safety()).isEqualTo(15);
        assertThat(atDc.allocated()).isEqualTo(5);
        assertThat(atDc.available()).isEqualTo(80);
        assertThat(atDc.confidence()).isEqualTo(1.0);
        assertThat(inventory.atp(sku, store).confidence()).isEqualTo(0.8);
        assertThat(inventory.atp(sku, "unknown").available()).isZero();

        config.set(InventoryConfigKeys.INCLUDE_INBOUND, true);
        assertThat(inventory.atp(sku, dc).available()).isEqualTo(120);

        List<Event> synced = events.load("node", dc);
        assertThat(synced).extracting(Event::type).contains("inventory.synced");
        Map<String, Object> payload = synced.get(synced.size() - 1).payload();
        assertThat(payload).containsEntry("source", "wms").containsEntry("positions", 4).containsEntry("skus", 1);
    }

    @Test
    void networkAtpSumsNodesAndAppliesChannelBuffer() {
        String sku = sku(), other = sku();
        inventory.upsert(List.of(
            InventoryPosition.onHand(sku, node(), 7),
            InventoryPosition.onHand(sku, node(), 3),
            InventoryPosition.onHand(other, node(), 1)), "pos");

        Map<String, Integer> net = inventory.networkAtp(List.of(sku, other, "nope"));
        assertThat(net).containsEntry(sku, 10).containsEntry(other, 1).containsEntry("nope", 0);

        config.set(InventoryConfigKeys.CHANNEL_BUFFER, 0.15);
        assertThat(inventory.networkAtp(List.of(sku))).containsEntry(sku, 8);

        // a write invalidates the cached figure immediately
        inventory.adjust(sku, inventory.atp(sku).get(0).nodeId(), 10, "cycle count", Actor.user("tester"));
        config.set(InventoryConfigKeys.CHANNEL_BUFFER, 0.0);
        assertThat(inventory.networkAtp(List.of(sku))).containsEntry(sku, 20);
    }

    @Test
    void staleFeedIsIgnoredAndConfidenceSurvivesSync() {
        String sku = sku(), n = node();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 50, 0, t0.plusSeconds(60))), "wms");
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 10, 0, t0)), "wms");   // older observation
        assertThat(inventory.atp(sku, n).onHand()).isEqualTo(50);

        confidence.set(sku, n, 0.42);
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 55, 0, t0.plusSeconds(120))), "wms");
        Atp atp = inventory.atp(sku, n);
        assertThat(atp.onHand()).isEqualTo(55);
        assertThat(atp.confidence()).isEqualTo(0.42);
        assertThat(events.load("node", n)).extracting(Event::type).contains("inventory.confidence_updated");
    }

    @Test
    void adjustWritesEventAndChangesOnHand() {
        String sku = sku(), n = node();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, n, 10)), "wms");
        inventory.adjust(sku, n, -4, "damaged", Actor.user("ops"));
        assertThat(inventory.atp(sku, n).onHand()).isEqualTo(6);
        Event adjusted = events.load("node", n).stream().filter(e -> e.type().equals("inventory.adjusted")).findFirst().orElseThrow();
        assertThat(adjusted.payload()).containsEntry("sku", sku).containsEntry("delta", -4).containsEntry("onHandAfter", 6).containsEntry("reason", "damaged");
        assertThat(adjusted.actor().ref()).isEqualTo("user:ops");
    }

    @Test
    void reconcileReportsDriftBeyondToleranceAndLogsIt() {
        String sku = sku(), good = sku(), n = node();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, n, 10), InventoryPosition.onHand(good, n, 5)), "wms");
        config.set(InventoryConfigKeys.DRIFT_TOLERANCE, 1);

        ReconciliationReport report = inventory.reconcile(List.of(
            InventoryPosition.onHand(sku, n, 7),          // stored 10 -> delta +3, beyond tolerance
            InventoryPosition.onHand(good, n, 4),         // stored 5 -> delta +1, within tolerance
            new InventoryPosition(sku, n, "po", null, 99, 0, null)));   // not on-hand: ignored

        assertThat(report.checked()).isEqualTo(2);
        assertThat(report.drifted()).containsExactly(new ReconciliationReport.Drift(sku, n, 7, 10, 3));
        assertThat(report.clean()).isFalse();

        Event drift = events.load("node", n).stream().filter(e -> e.type().equals("inventory.drift_detected")).findFirst().orElseThrow();
        assertThat(drift.payload()).containsEntry("tolerance", 1).containsKey("syncLogId");
        Integer logged = jdbc.sql("select count(*) from inventory_sync_log where tenant_id = :t and source = 'reconcile' and drift is not null and id = :id")
            .param("t", TENANT).param("id", drift.payload().get("syncLogId")).query(Integer.class).single();
        assertThat(logged).isEqualTo(1);

        assertThat(inventory.reconcile(List.of(InventoryPosition.onHand(good, n, 5))).clean()).isTrue();
    }
}
