package ai.intentoms.statemachine;

import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import ai.intentoms.statemachine.service.RollupEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RollupEvaluatorTest {
    private final RollupEvaluator rollup = new RollupEvaluator(new CelEngine());
    private final StateModel header = StateModelParser.parse(TestModels.demo("order.standard_ship.yaml"));

    @Test
    void derivesHeaderStatesFromLineQuantities() {
        assertThat(rollup.evaluate(header, Map.of("created", 10))).isEqualTo("created");
        assertThat(rollup.evaluate(header, Map.of("awaiting_sourcing", 10))).isEqualTo("sourcing");
        assertThat(rollup.evaluate(header, Map.of("released", 7, "backordered", 3))).isEqualTo("sourcing");
        assertThat(rollup.evaluate(header, Map.of("released", 7, "picking", 3))).isEqualTo("released");
        assertThat(rollup.evaluate(header, Map.of("shipped", 4, "released", 3, "backordered", 3))).isEqualTo("partially_shipped");
        assertThat(rollup.evaluate(header, Map.of("shipped", 8, "cancelled", 2))).isEqualTo("shipped");
        assertThat(rollup.evaluate(header, Map.of("shipped", 10))).isEqualTo("shipped");
        assertThat(rollup.evaluate(header, Map.of("delivered", 4, "shipped", 6))).isEqualTo("partially_delivered");
        assertThat(rollup.evaluate(header, Map.of("delivered", 9, "cancelled", 1))).isEqualTo("delivered");
        assertThat(rollup.evaluate(header, Map.of("cancelled", 10))).isEqualTo("cancelled");
    }

    @Test
    void priorityOrderFirstMatchWins() {
        StateModel m = StateModelParser.parse("""
            stateModel: p
            version: 1
            entity: order
            states: [ { id: created, meta: Created }, { id: low }, { id: high }, { id: done, meta: Terminal } ]
            transitions:
              - { from: created, to: low, trigger: event.a }
              - { from: low, to: high, trigger: event.b }
              - { from: high, to: done, trigger: event.c }
            rollup:
              - { parentState: low,  when: "ctx.total > 0", priority: 50 }
              - { parentState: high, when: "ctx.total > 5", priority: 10 }
              - { parentState: done, when: "ctx.total > 5", priority: 10 }
            """);
        assertThat(rollup.evaluate(m, Map.of("x", 3))).isEqualTo("low");
        assertThat(rollup.evaluate(m, Map.of("x", 9))).isEqualTo("high"); // priority 10 beats 50; declaration order breaks the tie
    }

    @Test
    void ruleEvaluationErrorCountsAsNoMatchAndFallbackApplies() {
        StateModel m = StateModelParser.parse("""
            stateModel: p
            version: 1
            entity: order
            states: [ { id: created, meta: Created }, { id: shipped }, { id: delivered, meta: Terminal }, { id: cancelled, meta: Terminal } ]
            transitions:
              - { from: created, to: shipped, trigger: event.a }
              - { from: shipped, to: delivered, trigger: event.b }
              - { from: shipped, to: cancelled, trigger: event.c }
            rollup:
              - { parentState: shipped, when: "state.picking > 0" }
            """);
        // state.picking is not a header state and not provided -> evaluation error -> fallback (non-terminal with most qty)
        assertThat(rollup.evaluate(m, Map.of("shipped", 2, "delivered", 5))).isEqualTo("shipped");
        // all terminal -> terminal with most qty
        assertThat(rollup.evaluate(m, Map.of("delivered", 5, "cancelled", 7))).isEqualTo("cancelled");
        // zero-filled child states make the rule evaluable
        assertThat(rollup.evaluate(m, Map.of("picking", 1, "delivered", 5), List.of("picking"), List.of())).isEqualTo("shipped");
        // child terminal hint used for ids the header does not know
        assertThat(rollup.evaluate(m, Map.of("picked_up", 3, "delivered", 1), List.of(), List.of("picked_up"))).isEqualTo("picked_up");
        assertThat(rollup.evaluate(m, Map.of())).isEqualTo("created");
    }

    @Test
    void ctxHelpersAndLinesAreBound() {
        Map<String, Object> vars = RollupEvaluator.vars(header, Map.of("shipped", 4, "released", 6), List.of());
        @SuppressWarnings("unchecked") Map<String, Object> ctx = (Map<String, Object>) vars.get("ctx");
        assertThat(ctx.get("total")).isEqualTo(10L);
        assertThat(ctx.get("distinct")).isEqualTo(2L);
        assertThat(ctx.get("max_state")).isEqualTo("released");
        assertThat(ctx.get("single_state")).isEqualTo(false);
        @SuppressWarnings("unchecked") List<String> withQty = (List<String>) ctx.get("states_with_qty");
        assertThat(withQty).containsExactlyInAnyOrder("shipped", "released");
        assertThat((List<?>) vars.get("lines")).hasSize(2);
        @SuppressWarnings("unchecked") Map<String, Object> state = (Map<String, Object>) vars.get("state");
        assertThat(state.get("delivered")).isEqualTo(0L);
        assertThat(state.get("shipped")).isEqualTo(4L);
    }
}
