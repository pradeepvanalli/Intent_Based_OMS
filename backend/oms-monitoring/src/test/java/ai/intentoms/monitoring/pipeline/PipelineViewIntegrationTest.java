package ai.intentoms.monitoring.pipeline;

import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.order.model.Order;
import ai.intentoms.statemachine.HoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-01: the pipeline view has the UI contract shape and its cells/age buckets/filters reflect the orders. */
class PipelineViewIntegrationTest extends MonitoringTestBase {
    @Autowired PipelineViewService pipeline;
    @Autowired HoldService holds;

    @Test
    void shapeCellsFiltersAndHeld() {
        String channel = "ch_" + System.nanoTime();
        Order young = newOrder(channel, "released", true);
        Order old = newOrder(channel, "released", true);
        Order created = newOrder(channel, "created", false);
        backdateState(old.id(), Duration.ofHours(6));
        holds.place("manual", "order", old.id(), Set.of("release"), "test", null, null, SYSTEM);
        pipeline.invalidate();

        PipelineView v = pipeline.pipeline(new PipelineFilters(channel, null, null));
        assertThat(v.ageBuckets()).containsExactly("<1h", "1-4h", "4-24h", "1-3d", ">3d");
        assertThat(v.states()).containsExactly("created", "released");
        assertThat(v.openOrders()).isEqualTo(3);
        assertThat(v.held()).isEqualTo(1);
        assertThat(v.byChannel()).containsEntry(channel, 3L);
        assertThat(v.byOrderType()).containsEntry("standard_ship", 3L);
        assertThat(v.byNode()).containsEntry("DC-EAST", 3L);
        assertThat(v.generatedAt()).isNotNull();
        assertThat(v.cells()).hasSize(2 * 5);
        Map<String, Map<String, Long>> m = PipelineViewService.asMatrix(v);
        assertThat(m.get("released")).containsEntry("<1h", 1L).containsEntry("4-24h", 1L).containsEntry("1-4h", 0L);
        assertThat(m.get("created")).containsEntry("<1h", 1L);

        PipelineView byNode = pipeline.pipeline(new PipelineFilters(channel, "standard_ship", "DC-EAST"));
        assertThat(byNode.openOrders()).isEqualTo(3);
        assertThat(pipeline.pipeline(new PipelineFilters(channel, "bopis", null)).openOrders()).isZero();

        // cached for a few seconds: a new order does not show until the cache expires or is invalidated
        newOrder(channel, "released", true);
        assertThat(pipeline.pipeline(new PipelineFilters(channel, null, null)).openOrders()).isEqualTo(3);
        pipeline.invalidate();
        assertThat(pipeline.pipeline(new PipelineFilters(channel, null, null)).openOrders()).isEqualTo(4);
        assertThat(young.id()).isNotEqualTo(created.id());
    }
}
