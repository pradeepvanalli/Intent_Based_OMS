package ai.intentoms.simulation.replay;

import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.intentoms.simulation.replay.LedgerFixtures.ORDER;
import static ai.intentoms.simulation.replay.LedgerFixtures.ledger;
import static ai.intentoms.simulation.replay.LedgerFixtures.qty;
import static org.assertj.core.api.Assertions.assertThat;

/** FR-SIM-01: recorded transitions folded through a proposed state model, including a blocked trigger. */
class StateModelReplayTest {
    static final String LINE = "order_line.standard_ship";
    static final String L1 = ORDER + ":L1";

    static final String PROPOSED_WITHOUT_VALIDATION = """
        stateModel: order_line.standard_ship
        version: 4
        entity: order_line
        quantityLevel: true
        states:
          - { id: created, meta: Created }
          - { id: awaiting_sourcing }
          - { id: released }
          - { id: shipped }
          - { id: delivered, meta: Terminal }
          - { id: cancelled, meta: Terminal }
        transitions:
          - { from: created, to: released, trigger: decision.sourcing_plan }
          - { from: released, to: awaiting_sourcing, trigger: event.store_rejected }
          - { from: awaiting_sourcing, to: released, trigger: decision.sourcing_plan }
          - { from: released, to: shipped, trigger: event.shipped }
          - { from: shipped, to: delivered, trigger: event.delivered }
          - { from: [created, awaiting_sourcing, released], to: cancelled, trigger: api.cancel_line, permission: order.cancel }
        """;

    static final String PROPOSED_RENAMED_STATES = """
        stateModel: order_line.standard_ship
        version: 5
        entity: order_line
        quantityLevel: true
        states:
          - { id: created, meta: Created }
          - { id: validated }
          - { id: allocated }
          - { id: shipped }
          - { id: delivered, meta: Terminal }
        transitions:
          - { from: created, to: validated, trigger: event.order_validated }
          - { from: validated, to: allocated, trigger: decision.sourcing_plan }
          - { from: allocated, to: shipped, trigger: event.shipped }
          - { from: shipped, to: delivered, trigger: event.delivered }
        """;

    private static RebuiltOrder recordedHistory() {
        return LedgerFold.fold(ORDER, ledger()
            .initialized("order", ORDER, "order.standard_ship", 1, "created", 1)
            .initialized("order_line", L1, LINE, 3, "created", 4)
            .transitioned("order_line", L1, LINE, 3, "created", "awaiting_sourcing", 4, "event.order_validated", qty("created", 4), qty("created", 0, "awaiting_sourcing", 4))
            .transitioned("order_line", L1, LINE, 3, "awaiting_sourcing", "released", 4, "decision.sourcing_plan", qty("awaiting_sourcing", 4), qty("awaiting_sourcing", 0, "released", 4))
            .transitioned("order", ORDER, "order.standard_ship", 1, "created", "released", 1, "rollup", qty("created", 1), qty("released", 1))
            .transitioned("order_line", L1, LINE, 3, "released", "shipped", 4, "event.shipped", qty("released", 4), qty("released", 0, "shipped", 4))
            .events());
    }

    @Test
    void triggerMissingFromTheProposedModelIsReportedAsBlocked() {
        StateModel proposed = StateModelParser.parse(PROPOSED_WITHOUT_VALIDATION);
        StateModelReplayResult r = StateModelReplay.fold(recordedHistory(), proposed);

        assertThat(r.modelId()).isEqualTo(LINE);
        assertThat(r.currentVersion()).isEqualTo(3);
        assertThat(r.proposedVersion()).isEqualTo(4);
        assertThat(r.transitionsReplayed()).isEqualTo(3);           // the header roll-up belongs to another model and is ignored
        assertThat(r.blockedTransitions()).isEqualTo(1);
        StateModelReplayResult.Step blocked = r.steps().stream().filter(StateModelReplayResult.Step::blocked).findFirst().orElseThrow();
        assertThat(blocked.trigger()).isEqualTo("event.order_validated");
        assertThat(blocked.simulatedFrom()).isEqualTo("created");
        assertThat(blocked.reason()).contains("no transition on 'event.order_validated'");
        // The sourcing decision still applies from created in the proposed model, then the shipment follows.
        assertThat(r.steps()).filteredOn(s -> StateModelReplayResult.APPLIED.equals(s.status())).extracting(StateModelReplayResult.Step::simulatedTo).containsExactly("released", "shipped");
        assertThat(r.steps()).hasSize(3).noneMatch(s -> ORDER.equals(s.entityId()));
        assertThat(r.entityStatesBefore()).containsEntry(L1, "shipped");
        assertThat(r.entityStatesAfter()).containsEntry(L1, "shipped");
        assertThat(r.simulatedBuckets().get(L1)).containsExactlyEntriesOf(Map.of("shipped", 4));
        assertThat(r.unmappedStates()).isEmpty();
    }

    @Test
    void renamedStatesAreReportedAsUnmappedAndReplayContinuesFromTheTriggerMatch() {
        StateModel proposed = StateModelParser.parse(PROPOSED_RENAMED_STATES);
        StateModelReplayResult r = StateModelReplay.fold(recordedHistory(), proposed);

        assertThat(r.blockedTransitions()).isZero();
        assertThat(r.unmappedStates()).containsExactlyInAnyOrder("awaiting_sourcing", "released");
        assertThat(r.retargeted()).isEqualTo(2);
        assertThat(r.steps()).filteredOn(s -> StateModelReplayResult.UNMAPPED.equals(s.status())).hasSize(2);
        assertThat(r.entityStatesAfter()).containsEntry(L1, "shipped");
        assertThat(r.steps().get(1).simulatedFrom()).isEqualTo("validated");
        assertThat(r.steps().get(1).simulatedTo()).isEqualTo("allocated");
    }

    @Test
    void entitiesOfOtherModelsAreIgnored() {
        StateModel proposed = StateModelParser.parse(PROPOSED_WITHOUT_VALIDATION);
        StateModelReplayResult r = StateModelReplay.fold(recordedHistory(), proposed);
        assertThat(r.entityStatesBefore()).doesNotContainKey(ORDER);
        assertThat(r.simulatedBuckets()).containsOnlyKeys(L1);
    }
}
