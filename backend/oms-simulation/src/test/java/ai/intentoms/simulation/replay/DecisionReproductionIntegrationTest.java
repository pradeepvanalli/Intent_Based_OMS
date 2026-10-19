package ai.intentoms.simulation.replay;

import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DefinitionCache;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionRuntime;
import ai.intentoms.learning.learner.SourcingPolicyBandit;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.simulation.SimulationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF-04: a recorded decision replays identically from its log — its recorded version, its recorded input (with the
 * learned facts it used), its recorded calibration — even after a newer version went live; and the sourcing bandit
 * replays from its seed.
 */
class DecisionReproductionIntegrationTest extends SimulationTestBase {
    @Autowired DecisionReplayer replayer;
    @Autowired DecisionRuntime runtime;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired DefinitionCache definitionCache;
    @Autowired DecisionRecordRepository records;

    private DecisionRecord liveRecord(String orderId, String decision) {
        return replayer.recordsFor(orderId, false).stream().filter(r -> decision.equals(r.decision())).findFirst().orElseThrow();
    }

    @Test
    void aRecordedDecisionReplaysOnItsOwnVersionAfterANewerOneWentLive() throws Exception {
        Order o = sourcedOrder("Please ship everything together in one delivery");
        DecisionRecord rec = liveRecord(o.id(), "fulfilment_intent");

        // A newer version goes live: it acts on anything, so re-running through it would give a different band.
        String yaml = Files.readString(DEMO.resolve("decisions/fulfilment_intent.yaml"))
            .replace("act: { confidence_gte: 0.8 }", "act: { confidence_gte: 0.05 }").replace("confirm: { confidence_gte: 0.5 }", "confirm: { confidence_gte: 0.01 }")
            .replaceFirst("version: \\d+", "version: " + (rec.version() + 1));
        DecisionDefinition newer = new DecisionDefinitionParser().parse(yaml).withStatus("live", 0, "newer live version", null, null);
        definitions.insert(newer, "user:tester");
        definitions.setStatus("fulfilment_intent", rec.version(), "retired", null);
        definitionCache.invalidate("fulfilment_intent");

        DecisionDefinition target = replayer.target(rec, Map.of());
        assertThat(target.version()).isEqualTo(rec.version());
        DecisionResult replayed = replayer.rerun(rec, target, Mode.REPLAY, "test");
        assertThat(replayed.version()).isEqualTo(rec.version());
        assertThat(replayed.value()).isEqualTo(rec.value());
        assertThat(replayed.band()).isEqualTo(rec.band());
        assertThat(replayed.confidence()).isEqualTo(rec.confidence());
        assertThat(replayed.details()).containsEntry("replayOf", rec.id());

        // The replay through the newer version (an explicit override) is the counterfactual, and it does differ.
        DecisionResult counterfactual = replayer.rerun(rec, replayer.target(rec, Map.of("fulfilment_intent", rec.version() + 1)), Mode.REPLAY, "test");
        assertThat(counterfactual.version()).isEqualTo(rec.version() + 1);
        if (rec.band() != Band.ACT) assertThat(counterfactual.band()).isEqualTo(Band.ACT);
    }

    @Test
    void theRecordedCalibrationIsReappliedSoTheBandMatches() {
        Order o = sourcedOrder("Please ship everything together in one delivery");
        DecisionRecord live = liveRecord(o.id(), "fulfilment_intent");
        DecisionDefinition def = definitions.find("fulfilment_intent", live.version()).orElseThrow().definition();
        DecisionResult raw = runtime.evaluate(def, live.input(), new DecisionOptions("order", o.id(), o.id(), Mode.SIMULATION, def.version(), Actor.system("test"), Map.of()));

        // The record as a calibrated LIVE evaluation would have written it: raw confidence lifted to 0.97, so it acted.
        Map<String, Object> details = new LinkedHashMap<>(live.details());
        details.put("rawConfidence", raw.confidence());
        details.put("calibration", Map.of("bin", "0.50-0.60:+0.42", "learner", "confidence_calibrator"));
        String id = Ids.id();
        DecisionRecord calibrated = new DecisionRecord(id, live.decision(), live.version(), Mode.LIVE, live.subjectType(), live.subjectId(), live.correlationId(), live.layer(),
            Band.ACT, DecisionResult.ACT, raw.value(), live.answers(), 0.97, live.provider(), live.modelVersion(), live.inputHash(), live.state(), live.input(), live.rulesFired(),
            live.reasonCodes(), live.latencyMs(), 0, 0, false, null, live.actor(), details, null, null, null, null, Map.of(), Instant.now(), live.contextFeatures(),
            live.arms(), String.valueOf(raw.value()), 1.0, live.policyVersion());
        records.insert(calibrated);

        DecisionResult replayed = replayer.rerun(calibrated, replayer.target(calibrated, Map.of()), Mode.REPLAY, "test");
        assertThat(replayed.confidence()).isEqualTo(0.97);
        assertThat(replayed.band()).isEqualTo(Band.ACT);
        assertThat(replayed.value()).isEqualTo(raw.value());
        assertThat(replayed.details()).containsEntry("rawConfidence", raw.confidence());
        @SuppressWarnings("unchecked") Map<String, Object> cal = (Map<String, Object>) replayed.details().get("calibration");
        assertThat(cal).containsEntry("reappliedFrom", id);
    }

    @Test
    void theBanditReplaysFromItsSeed() {
        Order o = sourcedOrder("Please ship everything together in one delivery");
        DecisionRecord logged = liveRecord(o.id(), SourcingPolicyBandit.DECISION_NAME);
        assertThat(replayer.replayBandit(logged, Mode.REPLAY, "test").after().value()).isEqualTo(logged.chosenArm());

        // An acting record: the applied arm is the seeded draw, and the replay re-derives exactly that draw.
        List<String> arms = List.of("ship_complete", "split_up_to_k", "nearest_node");
        Map<String, Double> weights = Map.of("ship_complete", 0.6, "split_up_to_k", 0.3, "nearest_node", 0.1);
        long seed = 424242L;
        String drawn = SourcingPolicyBandit.replay(arms, weights, seed);
        DecisionRecord acted = banditRecord(o.id(), arms, weights, seed, true, drawn);
        ReplayResult.DecisionReplay r = replayer.replayBandit(acted, Mode.REPLAY, "test");
        assertThat(r.after().value()).isEqualTo(drawn);
        assertThat(r.flipped()).isFalse();
        assertThat(BanditReplay.replay(acted).reproduced()).isTrue();

        String other = arms.stream().filter(a -> !a.equals(drawn)).findFirst().orElseThrow();
        assertThat(BanditReplay.replay(banditRecord(o.id(), arms, weights, seed, true, other)).reproduced()).isFalse();
    }

    private DecisionRecord banditRecord(String orderId, List<String> arms, Map<String, Double> weights, long seed, boolean acted, String applied) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("acted", acted);
        details.put("gate", "ok");
        details.put("appliedPolicy", applied);
        details.put("weights", new LinkedHashMap<>(Map.of("ship_complete", weights.get("ship_complete"), "split_up_to_k", weights.get("split_up_to_k"), "nearest_node", weights.get("nearest_node"))));
        details.put("randomSeed", seed);
        DecisionRecord r = new DecisionRecord(Ids.prefixed("dr"), SourcingPolicyBandit.DECISION_NAME, 1, Mode.LIVE, "order", orderId, orderId,
            ai.intentoms.decision.model.Layer.L1_SYSTEM_ONE, Band.ACT, "act", applied, Map.of(), 0.7, "bandit", "fs-01", "h", Map.of(), Map.of(), List.of(), List.of(),
            0, 0, 0, false, null, "system:test", details, null, null, null, null, Map.of(), Instant.now(), Map.of(), arms, applied, 0.7, 1);
        records.insert(r);
        return r;
    }
}
