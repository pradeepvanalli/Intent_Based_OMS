package ai.intentoms.orchestration.escalation;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.learner.ConfidenceCalibrator;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DF-02 (audit C-N2): the confidence calibrator learns from live resolutions. A decision a person resolves was escalated,
 * so its record is L3_HUMAN; it still carries the model's answer and confidence, and the person's answer comes from the
 * real task completion ({@code HumanResolutionCapture}). Twenty-four such resolutions raise the calibrator's sample by
 * twenty-four and fill a bin; before, the calibrator skipped every L3_HUMAN record and its sample never grew.
 */
class CalibrationFromLiveEscalationsIntegrationTest extends OrchestrationTestBase {
    @Autowired DecisionService decisions;
    @Autowired DecisionEscalationRepository escalations;
    @Autowired ConfidenceCalibrator calibrator;
    @Autowired LearnedFactRepository facts;

    private static final Map<String, Object> INPUT = Map.of(
        "mandate", Map.of("instruction_text", "Buy one grey hoodie size M under 100 dollars", "spend_limit", 100.0, "scope", "apparel", "expires_at", "2026-12-31T00:00:00Z"),
        "order", Map.of("total", 68.0, "items_summary", "1 x HOOD-GRY-M", "ship_to_country", "US"),
        "agent", Map.of("trust_tier", 2));

    private int sample() {
        calibrator.sweepOnce();
        return facts.active(SubjectType.DECISION, "agent_mandate_match", FactKey.CALIBRATION_ERROR).map(LearnedFact::sampleSize).orElse(0);
    }

    @Test
    void liveEscalationsAPersonResolvedCalibrateTheModel() {
        int before = sample();
        List<DecisionResult> escalated = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            DecisionResult r = decisions.decide("agent_mandate_match", INPUT, DecisionOptions.live("order", "ord_df02_" + Ids.shortId(), SYSTEM));
            assertThat(r.requiresHuman()).isTrue();
            assertThat(r.layer()).isEqualTo(Layer.L3_HUMAN);
            escalated.add(r);
        }
        // People agree with the model on a third of them and choose the other answer on the rest.
        for (int i = 0; i < escalated.size(); i++) {
            DecisionResult r = escalated.get(i);
            String task = escalations.find(r.escalationId()).orElseThrow().taskId();
            if (i % 3 == 0) tasks.complete(task, "accept", "matches the mandate", Map.of());
            else tasks.complete(task, "override", "outside the mandate", Map.of("value", String.valueOf(!Boolean.parseBoolean(String.valueOf(r.value())))));
        }
        drainOutbox();
        DecisionRecord one = decisions.record(escalated.get(1).recordId());
        assertThat(one.layer()).as("the record a person resolved is the escalated one").isEqualTo(Layer.L3_HUMAN);
        assertThat(one.labels()).containsKey("human_resolution");

        int after = sample();
        assertThat(after - before).as("every live resolution is an observation").isEqualTo(24);
        LearnedFact curve = facts.active(SubjectType.DECISION, "agent_mandate_match", FactKey.CALIBRATION_CURVE).orElseThrow();
        assertThat(curve.sampleSize()).isEqualTo(after);
        assertThat(curve.value().toString()).as("the bin the 24 resolutions filled gets a correction").contains("-0.").contains(":");
    }
}
