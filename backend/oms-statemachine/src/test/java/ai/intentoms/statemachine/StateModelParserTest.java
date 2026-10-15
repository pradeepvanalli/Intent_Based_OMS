package ai.intentoms.statemachine;

import ai.intentoms.statemachine.model.MetaState;
import ai.intentoms.statemachine.model.StateDef;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import ai.intentoms.statemachine.model.TransitionDef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateModelParserTest {

    @Test
    void parsesAppendixA() {
        StateModel m = StateModelParser.parse(TestModels.APPENDIX_A);
        assertThat(m.id()).isEqualTo("order_line.standard_ship");
        assertThat(m.version()).isEqualTo(3);
        assertThat(m.entity()).isEqualTo("order_line");
        assertThat(m.quantityLevel()).isTrue();
        assertThat(m.states()).hasSize(9);
        assertThat(m.initial().id()).isEqualTo("created");
        assertThat(m.state("delivered")).get().extracting(StateDef::meta).isEqualTo(MetaState.TERMINAL);
        assertThat(m.state("awaiting_sourcing")).get().extracting(StateDef::meta).isEqualTo(MetaState.ACTIVE);

        StateDef released = m.state("released").orElseThrow();
        assertThat(released.sla().maxAge()).isEqualTo(Duration.ofHours(4));
        assertThat(released.sla().onBreach()).isEqualTo("playbook.store_backlog");
        assertThat(m.state("backordered").orElseThrow().sla().maxAge()).isEqualTo(Duration.ofDays(3));

        assertThat(m.transitions()).hasSize(5);
        TransitionDef release = m.transitionsFrom("awaiting_sourcing", "decision.sourcing_plan").get(0);
        assertThat(release.to()).isEqualTo("released");
        assertThat(release.guards()).containsExactly("plan.margin >= tenant.margin_floor || approval.present", "!order.holds.exists(h, 'release' in h.blocks)");
        assertThat(release.actions()).containsExactly("reserve_inventory", "send_release_to_wms");
        assertThat(release.id()).isEqualTo("awaiting_sourcing->released#decision.sourcing_plan");

        TransitionDef cancel = m.transitions().get(4);
        assertThat(cancel.from()).containsExactly("created", "awaiting_sourcing", "released");
        assertThat(cancel.permission()).isEqualTo("order.cancel");
        assertThat(cancel.isApi()).isTrue();
        assertThat(cancel.extras()).containsEntry("sla_hint", 42);
        assertThat(cancel.id()).isEqualTo("created,awaiting_sourcing,released->cancelled#api.cancel_line");
        assertThat(m.yaml()).isEqualTo(TestModels.APPENDIX_A);
    }

    @Test
    void parsesRollupRulesAndDefaultsPriority() {
        StateModel m = StateModelParser.parse("""
            stateModel: order.x
            version: 2
            entity: order
            states: [ { id: created, meta: Created }, { id: shipped, meta: Terminal } ]
            transitions: [ { from: created, to: shipped, trigger: event.shipped } ]
            rollup:
              - { parentState: shipped, when: "state.shipped == ctx.total", priority: 5 }
              - { parentState: created, when: "true" }
            """);
        assertThat(m.quantityLevel()).isFalse();
        assertThat(m.rollup()).hasSize(2);
        assertThat(m.rollup().get(0).priority()).isEqualTo(5);
        assertThat(m.rollup().get(1).priority()).isEqualTo(20);
        assertThat(m.rollup().get(1).expression()).isEqualTo("true");
    }

    @Test
    void acceptsStringStatesAndSingleGuard() {
        StateModel m = StateModelParser.parse("""
            stateModel: t
            version: 1
            entity: thing
            states: [ created, { id: done, meta: terminal } ]
            transitions: [ { from: created, to: done, trigger: event.x, guard: "ctx.ok" } ]
            """);
        assertThat(m.states()).extracting(StateDef::id).containsExactly("created", "done");
        assertThat(m.transitions().get(0).guards()).containsExactly("ctx.ok");
        assertThat(m.transitions().get(0).from()).isEqualTo(List.of("created"));
    }

    @Test
    void rejectsStructuralProblems() {
        assertThatThrownBy(() -> StateModelParser.parse("")).hasMessageContaining("empty");
        assertThatThrownBy(() -> StateModelParser.parse("version: 1\nstates: []")).hasMessageContaining("stateModel");
        assertThatThrownBy(() -> StateModelParser.parse("stateModel: x\nstates:\n  - { id: a, sla: { maxAge: 4 hours, onBreach: playbook.x } }"))
            .hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> StateModelParser.parse("stateModel: x\nstates:\n  - { id: a, sla: { onBreach: playbook.x } }"))
            .hasMessageContaining("maxAge");
        assertThatThrownBy(() -> StateModelParser.parse("stateModel: x\nstates:\n  - { id: a, meta: Weird }")).hasMessageContaining("meta state");
        assertThatThrownBy(() -> StateModelParser.parse("stateModel: x\nstates: [a]\ntransitions:\n  - { from: a, to: b }")).hasMessageContaining("trigger");
        assertThatThrownBy(() -> StateModelParser.parse("stateModel: x\nstates: [a]\ntransitions:\n  - { to: b, trigger: event.x }")).hasMessageContaining("from");
        assertThatThrownBy(() -> StateModelParser.parse("stateModel: x\nversion: three\nstates: [a]")).hasMessageContaining("integer");
        assertThatThrownBy(() -> StateModelParser.parse("stateModel: x\nstates: [a]\nrollup:\n  - { parentState: a }")).hasMessageContaining("when");
    }
}
