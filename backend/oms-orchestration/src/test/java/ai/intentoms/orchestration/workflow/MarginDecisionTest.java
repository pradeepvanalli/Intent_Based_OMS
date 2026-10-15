package ai.intentoms.orchestration.workflow;

import ai.intentoms.decision.model.Tier;
import ai.intentoms.orchestration.escalation.HumanTaskEscalationHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Branch logic of the margin-floor approval task (GR-01, FR-SRC-04) and escalation queue routing (§8 tiers). */
class MarginDecisionTest {
    private static final List<Map<String, Object>> ALTS = List.of(Map.of("planId", "p2", "policy", "ship_complete"), Map.of("planId", "p3", "policy", "pickup_consolidated"));

    @Test
    void approvedReleasesTheBelowFloorPlan() {
        assertThat(MarginDecision.decide("approved", Map.of(), ALTS).kind()).isEqualTo(MarginDecision.Kind.APPROVE);
        assertThat(MarginDecision.decide("Accept", null, null).kind()).isEqualTo(MarginDecision.Kind.APPROVE);
    }

    @Test
    void alternativePolicyIsTakenFromDetailsOrPlanId() {
        MarginDecision.Outcome byPolicy = MarginDecision.decide("alternative", Map.of("policy", "Pickup_Consolidated"), ALTS);
        assertThat(byPolicy.kind()).isEqualTo(MarginDecision.Kind.ALTERNATIVE);
        assertThat(byPolicy.policy()).isEqualTo("pickup_consolidated");
        MarginDecision.Outcome byPlan = MarginDecision.decide("rejected", Map.of("planId", "p2"), ALTS);
        assertThat(byPlan.kind()).isEqualTo(MarginDecision.Kind.ALTERNATIVE);
        assertThat(byPlan.policy()).isEqualTo("ship_complete");
        MarginDecision.Outcome best = MarginDecision.decide("use_alternative", Map.of(), ALTS);
        assertThat(best.policy()).isEqualTo("ship_complete");
    }

    @Test
    void rejectionWithoutAlternativeCancels() {
        assertThat(MarginDecision.decide("rejected", Map.of(), ALTS).kind()).isEqualTo(MarginDecision.Kind.CANCEL);
        assertThat(MarginDecision.decide("alternative", Map.of(), List.of()).kind()).isEqualTo(MarginDecision.Kind.CANCEL);
        assertThat(MarginDecision.decide(null, null, null).kind()).isEqualTo(MarginDecision.Kind.CANCEL);
    }

    @Test
    void escalationQueuesFollowTierAndDecisionName() {
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T3, "agent_mandate_match")).isEqualTo("risk_review");
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T3, "return_risk")).isEqualTo("risk_review");
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T3, "address_risk")).isEqualTo("risk_review");
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T3, "refund_above_limit")).isEqualTo("refund_approvals");
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T3, "appeasement")).isEqualTo("refund_approvals");
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T3, "cancel_whole_order")).isEqualTo("decision_escalations");
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T2, "return_risk")).isEqualTo("decision_escalations");
        assertThat(HumanTaskEscalationHandler.queueFor(Tier.T1, "sla_breach_risk")).isEqualTo("decision_escalations");
    }
}
