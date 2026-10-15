package ai.intentoms.sourcing.plan;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.sourcing.Fixtures;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.SourcingRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading {@code sourcing_node_score} results (section 9 rules 1, 5; NFR-05 degraded mode). */
class SoftScorerTest {
    static DecisionResult result(Layer layer, Band band, Map<String, Answer> answers, Map<String, Object> details) {
        return new DecisionResult("rec1", "sourcing_node_score", 1, layer, band, "DC-NJ", answers, 0.6, "confirm", false, null, List.of(), List.of(), 12, 0.0, "heuristic", "h1", details);
    }

    @Test
    void readsTopKRanking() {
        var r = result(Layer.L1_SYSTEM_ONE, Band.CONFIRM, Map.of(), Map.of("topK", Map.of("ranking", List.of(
            Map.of("id", "DC-NJ", "score", 0.9, "confidence", 0.6), Map.of("id", "DC-TX", "score", 0.4, "confidence", 0.6)))));
        var s = SoftScorer.fromResult(r);
        assertThat(s.byNode()).containsEntry("DC-NJ", 0.9).containsEntry("DC-TX", 0.4);
        assertThat(s.source()).isEqualTo("topK");
        assertThat(s.decisionRecordId()).isEqualTo("rec1");
        assertThat(SoftScorer.floorScore(s.byNode())).isEqualTo(0.4);
    }

    @Test
    void fallsBackToRankingProbabilitiesRescaledToMaxOne() {
        var r = result(Layer.L1_SYSTEM_ONE, Band.ACT, Map.of("ranking", Answer.choice("DC-NJ", Map.of("DC-NJ", 0.6, "DC-TX", 0.3), 0.8)), Map.of());
        var s = SoftScorer.fromResult(r);
        assertThat(s.byNode()).containsEntry("DC-NJ", 1.0).containsEntry("DC-TX", 0.5);
        assertThat(s.source()).isEqualTo("answers.ranking");
    }

    @Test
    void defaultLayerAndEscalateBandGiveNeutralScores() {
        var dflt = SoftScorer.fromResult(result(Layer.DEFAULT, Band.ESCALATE, Map.of(), Map.of("defaultReason", "provider_unavailable")));
        assertThat(dflt.byNode()).isEmpty();
        assertThat(dflt.source()).isEqualTo("default");
        assertThat(dflt.notes().get(0)).contains("provider_unavailable");
        assertThat(SoftScorer.floorScore(dflt.byNode())).isEqualTo(PlanScorer.NEUTRAL_SOFT);
        var esc = SoftScorer.fromResult(result(Layer.L1_SYSTEM_ONE, Band.ESCALATE, Map.of(), Map.of("topK", Map.of("ranking", List.of(Map.of("id", "DC-NJ", "score", 0.9))))));
        assertThat(esc.byNode()).isEmpty();
        assertThat(esc.source()).isEqualTo("escalate");
    }

    @Test
    void inputCarriesTheDeclaredStateOnly() {
        SourcingRequest req = Fixtures.request("ground", "10001", "NY", List.of(Fixtures.line("L1", "A", 2, 50, 20)));
        Candidate c = Fixtures.candidate(Fixtures.dc("DC-NJ", 40.5187, -74.4121, "NJ", 1.1), req, Map.of("A", 5), Fixtures.demoCard());
        Map<String, Object> in = SoftScorer.input(req, List.of(c), Fixtures.demoCard(), Fixtures.NOON_UTC);
        assertThat(in).containsOnlyKeys("order", "candidates");
        @SuppressWarnings("unchecked") Map<String, Object> order = (Map<String, Object>) in.get("order");
        assertThat(order).containsEntry("destination_state", "NY").containsEntry("ship_method", "ground").containsEntry("line_count", 1);
        @SuppressWarnings("unchecked") Map<String, Object> cand = ((List<Map<String, Object>>) in.get("candidates")).get(0);
        assertThat(cand).containsOnlyKeys("id", "type", "backlog_units", "reject_rate", "staffing", "minutes_to_cutoff", "distance_km", "cost_usd");
        assertThat((long) cand.get("minutes_to_cutoff")).isEqualTo(5 * 60); // 12:00 New York → 17:00 cutoff
        assertThat((double) cand.get("cost_usd")).isEqualTo(8.10);         // 1 kg, zone 1
    }
}
