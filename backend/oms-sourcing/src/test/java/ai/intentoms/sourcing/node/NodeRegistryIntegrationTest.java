package ai.intentoms.sourcing.node;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.SourcingTestBase;
import ai.intentoms.sourcing.model.Node;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Node loader idempotence, health hook (FR-SRC-08), penalty learning and decay (FR-SRC-07). */
class NodeRegistryIntegrationTest extends SourcingTestBase {
    @Autowired NodeRegistry registry;
    @Autowired DefaultNodeRegistry impl;

    @Test
    void loaderIsIdempotentAndNodesRoundTrip() {
        assertThat(registry.active()).hasSize(8);
        nodeLoader.loadFile(DEMO.resolve("nodes.yaml"));
        assertThat(registry.all()).hasSize(8);
        Node nj = registry.get("DC-NJ");
        assertThat(nj.capabilities()).containsExactlyInAnyOrder("ship", "hazmat", "same_day");
        assertThat(nj.cutoff()).isEqualTo(java.time.LocalTime.of(17, 0));
        assertThat(nj.timezone()).isEqualTo("America/New_York");
        assertThat(nj.rejectRate()).isEqualTo(0.004);
        assertThat(nj.costNumber("labour_per_unit", 0)).isEqualTo(1.10);
        assertThat(nj.penalty()).isZero();
        assertThat(registry.get("VND-MX").customsOrigin()).isEqualTo("MX");
        assertThatThrownBy(() -> registry.get("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void healthUpdateMergesSignalsAndWritesEvent() {
        Node n = registry.updateHealth("ST-CHI-01", Map.of("backlog", 80, "weather", "snow"));
        assertThat(n.health()).containsEntry("backlog", 80).containsEntry("weather", "snow").containsEntry("staffing", 0.7);
        assertThat(registry.get("ST-CHI-01").backlogUnits()).isEqualTo(80);
        var evts = events("node", "ST-CHI-01", DefaultNodeRegistry.EVT_HEALTH_UPDATED);
        assertThat(evts).hasSize(1);
        assertThat(evts.get(0)).containsKeys("signals", "health");
    }

    @Test
    void penaltiesAccumulatePerSkuAndDecayWithSevenDayHalfLife() {
        registry.penalise("ST-AUS-03", "TEE-BLK-M", 0.3, "store_reject");
        registry.penalise("ST-AUS-03", null, 0.2, "late pickups");
        assertThat(registry.penalty("ST-AUS-03", "TEE-BLK-M")).isCloseTo(0.5, within(1e-6));
        assertThat(registry.penalty("ST-AUS-03", "OTHER")).isCloseTo(0.2, within(1e-6));
        assertThat(registry.get("ST-AUS-03").penalty()).isCloseTo(0.2, within(1e-6));
        registry.penalise("ST-AUS-03", "TEE-BLK-M", 0.9, "again");
        assertThat(registry.penalty("ST-AUS-03", "TEE-BLK-M")).isEqualTo(1.0);          // clamped
        var evts = events("node", "ST-AUS-03", DefaultNodeRegistry.EVT_PENALISED);
        assertThat(evts).hasSize(3);
        assertThat(evts.get(0)).containsEntry("sku", "TEE-BLK-M").containsEntry("delta", 0.3).containsEntry("penaltyAfter", 0.3);

        time.override(Clock.fixed(NOW.plus(Duration.ofDays(7)), ZoneOffset.UTC));
        impl.invalidate();
        assertThat(registry.penalty("ST-AUS-03", "OTHER")).isCloseTo(0.1, within(1e-6));
        assertThat(registry.get("ST-AUS-03").penalty()).isCloseTo(0.1, within(1e-6));
        time.override(Clock.fixed(NOW.plus(Duration.ofDays(70)), ZoneOffset.UTC));
        impl.invalidate();
        assertThat(registry.penalty("ST-AUS-03", "TEE-BLK-M")).isLessThan(0.002);
        assertThat(impl.penaltyRows("ST-AUS-03")).hasSize(2).allSatisfy(r -> assertThat((double) r.get("effective")).isLessThan((double) r.get("penalty")));
    }

    @Test
    void upsertReplacesDefinitionAndWritesEvent() {
        Node n = registry.get("DC-TX").withActive(false);
        registry.upsert(n);
        assertThat(registry.active()).extracting(Node::id).doesNotContain("DC-TX");
        assertThat(registry.all()).extracting(Node::id).contains("DC-TX");
        assertThat(events("node", "DC-TX", DefaultNodeRegistry.EVT_UPSERTED)).hasSize(1);
    }
}
