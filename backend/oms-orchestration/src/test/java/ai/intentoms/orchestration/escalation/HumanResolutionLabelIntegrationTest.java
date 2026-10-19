package ai.intentoms.orchestration.escalation;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.learner.ProviderRoutingLearner;
import ai.intentoms.learning.model.HumanResolution;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OC-06 / audit defect C2: completing an escalation task with the options it really offers ("accept" / "override" with
 * {@code details.value}) labels the decision with the value the person chose, so learners that read human resolutions
 * (golden-set growth, override-to-rule, provider routing, rule utilisation) see the answer, not the button.
 */
class HumanResolutionLabelIntegrationTest extends OrchestrationTestBase {
    @Autowired DecisionService decisions;
    @Autowired DecisionEscalationRepository escalations;

    private static final Map<String, Object> INPUT = Map.of(
        "mandate", Map.of("instruction_text", "Buy one grey hoodie size M under 100 dollars", "spend_limit", 100.0, "scope", "apparel", "expires_at", "2026-12-31T00:00:00Z"),
        "order", Map.of("total", 68.0, "items_summary", "1 x HOOD-GRY-M", "ship_to_country", "US"),
        "agent", Map.of("trust_tier", 2));

    private DecisionResult escalate() {
        DecisionResult r = decisions.decide("agent_mandate_match", INPUT, DecisionOptions.live("order", "ord_c2_" + Ids.shortId(), SYSTEM));
        assertThat(r.requiresHuman()).isTrue();
        return r;
    }

    private String taskOf(DecisionResult r) { return escalations.find(r.escalationId()).orElseThrow().taskId(); }

    @Test
    void overridesRecordTheChosenValueAndAcceptsRecordTheRecommendation() {
        List<DecisionResult> overridden = List.of(escalate(), escalate(), escalate());
        for (DecisionResult r : overridden) tasks.complete(taskOf(r), "override", "outside the buyer's instruction", Map.of("value", "ship_complete"));
        DecisionResult accepted = escalate();
        tasks.complete(taskOf(accepted), "accept", "matches", Map.of());
        drainOutbox();

        for (DecisionResult r : overridden) {
            DecisionRecord rec = decisions.record(r.recordId());
            Map<String, Object> label = HumanResolution.label(rec).orElseThrow();
            assertThat(label).containsEntry("value", "ship_complete").containsEntry("outcome", "override").containsEntry("reasonCode", "outside the buyer's instruction");
            assertThat(HumanResolution.value(rec)).contains("ship_complete");
        }
        // Three people chose the same answer: 100% agreement on the value, whatever button they pressed.
        assertThat(overridden.stream().map(r -> HumanResolution.value(decisions.record(r.recordId())).orElse("?")).distinct()).containsExactly("ship_complete");

        DecisionRecord acc = decisions.record(accepted.recordId());
        assertThat(HumanResolution.label(acc).orElseThrow()).containsEntry("outcome", "accept");
        assertThat(HumanResolution.value(acc)).contains(String.valueOf(acc.value()));
        assertThat(HumanResolution.agreedWithModel(acc)).isEqualTo(Optional.of(true));
        // Provider routing scores the reviewer who agreed as a hit, and the three overrides (a different value) as misses.
        assertThat(ProviderRoutingLearner.accuracy(List.of(acc))).isEqualTo(1.0);
        List<DecisionRecord> all = new java.util.ArrayList<>(overridden.stream().map(r -> decisions.record(r.recordId())).toList());
        all.add(acc);
        long overridesTheModelGotRight = all.subList(0, 3).stream().filter(r -> "ship_complete".equalsIgnoreCase(String.valueOf(r.value()))).count();
        assertThat(ProviderRoutingLearner.accuracy(all)).isEqualTo((1 + overridesTheModelGotRight) / 4.0);
    }
}
