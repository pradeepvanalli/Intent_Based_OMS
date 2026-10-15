package ai.intentoms.simulation.replay;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.simulation.SimulationTestBase;
import ai.intentoms.simulation.whatif.ScenarioOverrides;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.StateModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design principle 4 and FR-SIM-01 against PostgreSQL: rebuild consistency, decision replay with a draft version, proposed state model, re-sourcing. */
class ReplayIntegrationTest extends SimulationTestBase {
    @Autowired OrderRebuilder rebuilder;
    @Autowired ReplayService replays;
    @Autowired DecisionStudioService studio;
    @Autowired StateModelRegistry stateModelRegistry;
    @Autowired HoldService holds;

    @Test
    void rebuildFromTheLedgerIsConsistentWithTheLiveProjection() {
        Order o = sourcedOrder("Please ship everything together in one delivery");
        holds.place("manual", "order", o.id(), Set.of("release"), "CS_REVIEW", "customer asked to wait", null, Actor.user("tester"));

        RebuiltOrder r = rebuilder.rebuild(o.id());
        assertThat(r.channel()).isEqualTo("web");
        assertThat(r.orderType()).isEqualTo("standard_ship");
        assertThat(r.entities()).hasSize(3);
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.states()).containsExactlyEntriesOf(Map.of("awaiting_sourcing", l.initialQty())));
        assertThat(r.headerStatus()).isEqualTo("sourcing");
        assertThat(r.holds()).singleElement().satisfies(h -> assertThat(h.reasonCode()).isEqualTo("CS_REVIEW"));
        assertThat(r.intent()).isNotNull();
        assertThat(r.latestPlan()).isNotNull();
        assertThat(r.latestPlan().nodeIds()).containsExactly("DC-NJ");
        assertThat(r.decisionRecordIds()).isNotEmpty();
        assertThat(r.transitions()).anyMatch(t -> "event.order_validated".equals(t.trigger())).anyMatch(RebuiltOrder.RecordedTransition::isRollup);

        ConsistencyReport report = rebuilder.check(o.id());
        assertThat(report.differences()).isEmpty();
        assertThat(report.consistent()).isTrue();
        assertThat(report.rebuilt().orderId()).isEqualTo(o.id());

        // A projection drift (someone edits the status column) is caught.
        jdbc.sql("update orders set status = 'delivered' where tenant_id = :t and id = :id").param("t", tenant).param("id", o.id()).update();
        ConsistencyReport drifted = rebuilder.check(o.id());
        assertThat(drifted.consistent()).isFalse();
        assertThat(drifted.differences()).anyMatch(d -> "header_status".equals(d.kind()) && "sourcing".equals(d.rebuilt()) && "delivered".equals(d.live()));

        assertThatThrownBy(() -> rebuilder.rebuild("ord_missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void replayWithADraftVersionReportsBandChangesAndPersistsReplayRecords() throws Exception {
        Order o = sourcedOrder("Please ship everything together in one delivery");
        assertThat(records(o.id(), "LIVE")).extracting(r -> r.get("decision")).contains("fulfilment_intent", "sourcing_node_score");

        // v2 lowers the act threshold so the heuristic provider's 0.55-0.6 confidence now acts (FR-PRM-02 draft).
        String yaml = Files.readString(DEMO.resolve("decisions/fulfilment_intent.yaml")).replace("act: { confidence_gte: 0.8 }", "act: { confidence_gte: 0.5 }");
        DecisionDefinition draft = studio.createDraft("fulfilment_intent", yaml);
        assertThat(draft.version()).isEqualTo(2);
        assertThat(draft.status()).isEqualTo("draft");

        ReplayResult current = replays.replay(o.id(), ReplayOptions.current());
        assertThat(current.decisions()).isNotEmpty().allMatch(d -> d.versionAfter() == d.versionBefore());
        assertThat(current.decisions()).filteredOn(d -> "fulfilment_intent".equals(d.decision())).singleElement().satisfies(d -> {
            assertThat(d.flipped()).isFalse();
            assertThat(d.bandChanged()).isFalse();
            assertThat(d.replayRecordId()).isNotBlank().isNotEqualTo(d.recordId());
        });
        assertThat(current.stateModel()).isNull();
        assertThat(current.stateBefore()).isEqualTo("sourcing");
        assertThat(current.stateAfter()).isEqualTo("sourcing");
        assertThat(current.sourcing()).isNotNull();
        assertThat(current.sourcing().before().nodeIds()).containsExactly("DC-NJ");
        assertThat(current.summary().decisionsReplayed()).isEqualTo(current.decisions().size());

        ReplayResult proposed = replays.replay(o.id(), ReplayOptions.decisions(Map.of("fulfilment_intent", 2)));
        ReplayResult.DecisionReplay intent = proposed.decisions().stream().filter(d -> "fulfilment_intent".equals(d.decision())).findFirst().orElseThrow();
        assertThat(intent.versionBefore()).isEqualTo(1);
        assertThat(intent.version()).isEqualTo(1);
        assertThat(intent.versionAfter()).isEqualTo(2);
        assertThat(intent.flipped()).isFalse();                          // same input, same heuristic answer
        if (intent.before().band() == Band.CONFIRM) {                     // the heuristic answer sits in the confirm band under v1 ...
            assertThat(intent.after().band()).isEqualTo(Band.ACT);        // ... and acts under v2
            assertThat(intent.bandChanged()).isTrue();
            assertThat(intent.changed()).isTrue();
            assertThat(intent.after().acts()).isTrue();
            assertThat(intent.before().acts()).isFalse();
            assertThat(proposed.summary().bandChanged()).isGreaterThanOrEqualTo(1);
        } else {
            assertThat(intent.before().band()).isEqualTo(Band.ESCALATE);  // escape option chosen: no threshold can make it act
            assertThat(intent.after().band()).isEqualTo(Band.ESCALATE);
            assertThat(intent.bandChanged()).isFalse();
        }
        // Other decisions were replayed against their current live version.
        assertThat(proposed.decisions()).filteredOn(d -> !"fulfilment_intent".equals(d.decision())).allMatch(d -> d.versionAfter() == d.versionBefore());

        // REPLAY-mode records exist for the order (they never act: no escalation, no cache), LIVE records are untouched.
        List<Map<String, Object>> replayRecords = records(o.id(), "REPLAY");
        assertThat(replayRecords).hasSize(current.decisions().size() + proposed.decisions().size());
        assertThat(replayRecords).anyMatch(r -> "fulfilment_intent".equals(r.get("decision")) && Integer.valueOf(2).equals(r.get("version")));
        assertThat(records(o.id(), "LIVE")).hasSizeGreaterThanOrEqualTo(current.decisions().size()); // re-sourcing in the replay adds LIVE sourcing_node_score records
        assertThat(jdbc.sql("select count(*) from decision_escalation where tenant_id = :t and record_id in (select id from decision_record where tenant_id = :t and mode = 'REPLAY')")
            .param("t", tenant).query(Long.class).single()).isZero();

        assertThatThrownBy(() -> replays.replay(o.id(), ReplayOptions.decisions(Map.of("fulfilment_intent", 99)))).isInstanceOf(ValidationException.class);
    }

    @Test
    void replayThroughAProposedStateModelReportsBlockedTriggersAndReSourcesUnderOverrides() throws Exception {
        Order o = sourcedOrder("Send each item as soon as it is ready");
        String v4 = Files.readString(DEMO.resolve("state-models/order_line.standard_ship.yaml"))
            .replace("version: 3", "version: 4")
            .replace("""
                  - from: created
                    to: awaiting_sourcing
                    trigger: event.order_validated
                """, """
                  - from: created
                    to: released
                    trigger: decision.sourcing_plan
                """);
        StateModel published = stateModelRegistry.publish(v4, "v4 drops the validation step: sourcing releases straight from created");
        assertThat(published.version()).isEqualTo(4);

        ReplayResult r = replays.replay(o.id(), new ReplayOptions(Map.of(), 4, null, ScenarioOverrides.nodeClosure(Set.of("DC-NJ")), false));

        assertThat(r.stateModel()).isNotNull();
        assertThat(r.stateModel().modelId()).isEqualTo("order_line.standard_ship");
        assertThat(r.stateModel().currentVersion()).isEqualTo(3);
        assertThat(r.stateModel().proposedVersion()).isEqualTo(4);
        assertThat(r.stateModel().transitionsReplayed()).isEqualTo(2);
        assertThat(r.stateModel().blockedTransitions()).isEqualTo(2);     // event.order_validated on both lines has no transition in v4
        assertThat(r.stateModel().steps()).filteredOn(StateModelReplayResult.Step::blocked).allMatch(s -> "event.order_validated".equals(s.trigger()));
        assertThat(r.stateModel().entityStatesBefore()).hasSize(2).containsValue("awaiting_sourcing");
        assertThat(r.stateModel().entityStatesAfter()).hasSize(2).containsValue("created");
        assertThat(r.stateModel().finalStateBefore()).isEqualTo("sourcing");
        assertThat(r.stateModel().finalStateAfter()).isEqualTo("created");
        assertThat(r.stateBefore()).isEqualTo("sourcing");
        assertThat(r.stateAfter()).isEqualTo("created");
        assertThat(r.summary().blockedTransitions()).isEqualTo(2);

        // Re-sourcing with DC-NJ closed moves the plan to Dallas and changes the cost.
        assertThat(r.sourcing()).isNotNull();
        assertThat(r.sourcing().error()).isNull();
        assertThat(r.sourcing().before().nodeIds()).containsExactly("DC-NJ");
        assertThat(r.sourcing().after().nodeIds()).doesNotContain("DC-NJ").isNotEmpty();
        assertThat(r.sourcing().after().policy()).isEqualTo(r.sourcing().before().policy());
        assertThat(r.sourcing().nodesChanged()).isTrue();
        assertThat(r.sourcing().costDelta()).isNotNull().isNotZero();
        assertThat(r.sourcing().overrides().closedNodes()).containsExactly("DC-NJ");

        assertThatThrownBy(() -> replays.replay(o.id(), new ReplayOptions(Map.of(), 42, null, null, false))).isInstanceOf(ValidationException.class);
    }
}
