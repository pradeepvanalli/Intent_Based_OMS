package ai.intentoms.decision.record;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.CustomerSafeTraceView;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionTrace;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.model.Tier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** XP-03 (the why drawer shows the learned facts and policy version a decision used) and XP-05 (customer-safe wording). */
class TraceLearningTest {
    private static DecisionRecord record(Map<String, Object> details) {
        return new DecisionRecord("dr_1", "sourcing_node_score", 4, Mode.LIVE, "order", "ord_1", "ord_1", Layer.L1_SYSTEM_ONE, Band.ACT, "act", "DC-NJ", Map.of(), 0.81,
            "heuristic", "h1", "hash", Map.of(), Map.of(), List.of(), List.of(), 12, 0, 0, false, null, "system:test", details, null, null, null, null, Map.of(), Instant.now(),
            Map.of(), List.of("DC-NJ", "STORE-ATX-01"), "DC-NJ", 1.0, 4);
    }

    private static final Map<String, Object> DETAILS = Map.of(
        "learnedFacts", List.of(
            Map.of("subjectType", "NODE", "subjectId", "DC-NJ", "factKey", "NODE_ON_TIME_RATE", "version", 7, "learned", 0.93, "served", 0.93, "source", "LEARNED", "reason", "promoted for live use"),
            Map.of("subjectType", "NODE", "subjectId", "DC-NJ", "factKey", "NODE_REJECT_RATE", "version", 7, "learned", 0.02, "served", 0.05, "source", "STATIC", "reason", "not promoted for live use"),
            Map.of("subjectType", "AGENT", "subjectId", "shopbot", "factKey", "AGENT_REPUTATION", "version", 2, "learned", 0.4, "served", 0.4, "source", "LEARNED", "reason", "promoted")),
        "rawConfidence", 0.74,
        "calibration", Map.of("factVersion", 3),
        "experiment", Map.of("experimentId", "exp_1", "arm", "canary", "version", 4),
        "providerRouted", "jev");

    @Test
    void traceLearningSectionCarriesFactsVersionsCalibrationAndExperiment() {
        Map<String, Object> l = TraceBuilder.learning(record(DETAILS));
        assertThat((List<?>) l.get("learnedFacts")).hasSize(3);
        assertThat(l).containsEntry("learnedFactsServed", 2L).containsEntry("policyVersion", 4).containsEntry("rawConfidence", 0.74)
            .containsEntry("calibratedConfidence", 0.81).containsEntry("providerRouted", "jev").containsEntry("usedLearning", true)
            .containsEntry("chosenArm", "DC-NJ").containsKey("experiment");
        assertThat(TraceBuilder.learning(record(Map.of()))).containsEntry("usedLearning", false).containsEntry("learnedFactsServed", 0L);
    }

    @Test
    void customerSafeContextNamesServedKnowledgeOnlyAndNeverRiskOrValues() {
        DecisionTrace t = new DecisionTrace("dr_1", "sourcing_node_score", 4, Tier.T1, Mode.LIVE, Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
            Tier.T1.defaultBands(), Band.ACT, "act", Layer.L1_SYSTEM_ONE, "DC-NJ", 0.81, false, List.of(), "heuristic", "h1", 12, 0, "system:test", null, null, Map.of(), DETAILS,
            Instant.now(), TraceBuilder.learning(record(DETAILS)));
        CustomerSafeTraceView v = CustomerSafeTraceView.from(t);
        assertThat(v.learnedContext()).containsExactly("This decision took into account how reliably each fulfilment location has shipped on time recently.");
        assertThat(String.join(" ", v.learnedContext())).doesNotContain("0.93").doesNotContainIgnoringCase("reputation").doesNotContainIgnoringCase("risk");
    }

    /** D5-2 / XP-05: the decision field itself is gated the same way value/questions/reasonCodes are. */
    @Test
    void customerSafeTraceNeverNamesTheRiskDecisionButKeepsRealNamesForNonInternalOnes() {
        for (String risky : List.of("payment_risk", "rto_risk", "return_risk", "address_risk", "sla_breach_risk")) {
            DecisionTrace t = new DecisionTrace("dr_risk", risky, 3, Tier.T1, Mode.LIVE, Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                Tier.T1.defaultBands(), Band.ESCALATE, "escalate", Layer.L1_SYSTEM_ONE, "high_risk", 0.81, true, List.of(), "heuristic", "h1", 12, 0, "system:test",
                null, null, Map.of(), Map.of(), Instant.now(), Map.of());
            CustomerSafeTraceView v = CustomerSafeTraceView.from(t);
            assertThat(v.decision()).as("decision field for a %s trace", risky)
                .isEqualTo("internal_review")
                .isNotIn("payment_risk", "rto_risk", "return_risk", "address_risk", "sla_breach_risk");
        }

        // Not sourcing_node_score here: its own name matches INTERNAL ("node"/"score"), by design — its value is a
        // node id, which this view already withholds (confirmed live below and by McpIntegrationTest), so hiding its
        // decision name too is the fix being consistent with existing behaviour, not a regression to guard against.
        DecisionTrace fulfilmentIntent = new DecisionTrace("dr_fi", "fulfilment_intent", 2, Tier.T1, Mode.LIVE, Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
            Tier.T1.defaultBands(), Band.ACT, "act", Layer.L1_SYSTEM_ONE, "ship_as_available", 0.81, false, List.of(), "heuristic", "h1", 12, 0, "system:test", null, null, Map.of(), Map.of(),
            Instant.now(), Map.of());
        assertThat(CustomerSafeTraceView.from(fulfilmentIntent).decision()).as("a non-internal decision keeps its real name").isEqualTo("fulfilment_intent");
    }
}
