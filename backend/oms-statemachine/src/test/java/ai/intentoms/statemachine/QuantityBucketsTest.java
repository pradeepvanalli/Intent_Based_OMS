package ai.intentoms.statemachine;

import ai.intentoms.statemachine.model.QuantityBucket;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import ai.intentoms.statemachine.service.Aggregates;
import ai.intentoms.statemachine.service.QuantityBuckets;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuantityBucketsTest {
    private final StateModel model = StateModelParser.parse(TestModels.demo("order_line.standard_ship.yaml"));

    @Test
    void movesQuantityBetweenBucketsAndDropsEmptyOnes() {
        Map<String, Integer> b = new LinkedHashMap<>(Map.of("awaiting_sourcing", 10));
        b = QuantityBuckets.move(b, "awaiting_sourcing", "released", 7);
        assertThat(b).containsExactlyInAnyOrderEntriesOf(Map.of("awaiting_sourcing", 3, "released", 7));
        b = QuantityBuckets.move(b, "awaiting_sourcing", "backordered", 3);
        assertThat(b).containsExactlyInAnyOrderEntriesOf(Map.of("released", 7, "backordered", 3));
        b = QuantityBuckets.move(b, "released", "shipped", 4);
        assertThat(b).containsExactlyInAnyOrderEntriesOf(Map.of("released", 3, "backordered", 3, "shipped", 4));
        assertThat(QuantityBuckets.total(b)).isEqualTo(10);
        Map<String, Integer> self = QuantityBuckets.move(b, "released", "released", 3);
        assertThat(self).isEqualTo(b);
    }

    @Test
    void rejectsImpossibleMoves() {
        Map<String, Integer> b = Map.of("released", 3);
        assertThatThrownBy(() -> QuantityBuckets.move(b, "released", "shipped", 4)).hasMessageContaining("insufficient").hasMessageContaining("have 3");
        assertThatThrownBy(() -> QuantityBuckets.move(b, "released", "shipped", 0)).hasMessageContaining("positive");
        assertThatThrownBy(() -> QuantityBuckets.move(b, "packed", "shipped", 1)).hasMessageContaining("packed");
    }

    @Test
    void dominantStatePrefersInFlightQuantityThenTerminal() {
        assertThat(QuantityBuckets.dominant(model, Map.of("shipped", 4, "released", 3, "backordered", 3))).isEqualTo("shipped");
        assertThat(QuantityBuckets.dominant(model, Map.of("delivered", 9, "released", 1))).isEqualTo("released");
        assertThat(QuantityBuckets.dominant(model, Map.of("delivered", 6, "cancelled", 4))).isEqualTo("delivered");
        assertThat(QuantityBuckets.dominant(model, Map.of("picking", 5, "released", 5))).isEqualTo("released"); // tie -> declaration order
        assertThat(QuantityBuckets.dominant(model, Map.of())).isEqualTo("created");
    }

    @Test
    void mapsBucketsAndBuildsCelStateMap() {
        Instant t = Instant.now();
        Map<String, Integer> m = QuantityBuckets.toMap(List.of(new QuantityBucket("released", 3, t), new QuantityBucket("shipped", 4, t), new QuantityBucket("picking", 0, t)));
        assertThat(m).containsExactlyInAnyOrderEntriesOf(Map.of("released", 3, "shipped", 4));
        Map<String, Object> cel = QuantityBuckets.celStateMap(model, m);
        assertThat(cel).containsEntry("released", 3L).containsEntry("shipped", 4L).containsEntry("backordered", 0L).containsEntry("created", 0L);
        assertThat(cel).hasSize(model.states().size());
    }

    @Test
    void aggregateMapping() {
        assertThat(Aggregates.of("order", "O1")).isEqualTo(new Aggregates.AggregateRef("order", "O1"));
        assertThat(Aggregates.of("order_line", "O1:L2")).isEqualTo(new Aggregates.AggregateRef("order", "O1"));
        assertThat(Aggregates.of("return_line", "rma_1:1")).isEqualTo(new Aggregates.AggregateRef("return", "rma_1"));
        assertThat(Aggregates.of("return", "rma_1")).isEqualTo(new Aggregates.AggregateRef("return", "rma_1"));
        assertThat(Aggregates.of("shipment", "S1")).isEqualTo(new Aggregates.AggregateRef("shipment", "S1"));
        assertThat(Aggregates.of("payment", "P1")).isEqualTo(new Aggregates.AggregateRef("payment", "P1"));
        assertThat(Aggregates.of("refund", "R1")).isEqualTo(new Aggregates.AggregateRef("refund", "R1"));
        assertThat(Aggregates.of("fulfilment_group", "FG1")).isEqualTo(new Aggregates.AggregateRef("fulfilment_group", "FG1"));
    }
}
