package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DF-07 on live-shaped data: "L1 outperforms the rule" must be reachable although a rule-fired record never asks a
 * person. The records here have exactly the live shape of a payment_risk L0 clearance (layer L0_RULE, acted, value
 * false, requires_human false, propensity 1, no human_resolution), and their only labels come from the real
 * {@link FraudLabelCapture} reading chargebacks and deliveries. Once their chargeback window has closed, those outcomes
 * are the ground truth: the clearance rule let 20 frauds through, the model without the rule (re-run in simulation as
 * the system principal) would have held them, so retiring the rule is proposed with the outcome evidence. A rule the
 * outcomes agree with is left alone. Before, the learner only read human resolutions and this rule could never be judged.
 */
class RuleRetirementFromOutcomesIntegrationTest extends DecisionFabricTestBase {
    @Autowired RuleUtilizationLearner ruleUtilization;
    @Autowired FraudLabelCapture fraudLabels;

    private String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "df07-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    @AfterEach
    void back() { TenantContext.set(TENANT); }

    private static final String YAML = """
        decision: payment_risk
        version: 1
        owner: test
        tier: T2
        provider: heuristic
        timeoutMs: 800
        default: false
        rules:
          - id: clean_velocity
            when: "input.payment.velocity_1h <= 1"
            then: false
            reason: clean_velocity
            priority: 10
        state:
          include:
            - payment.velocity_1h
            - payment.note
            - order.total
        questions:
          high_risk_payment:
            type: noul
            statement: The payment shows stolen card signals.
        bands:
          act: { confidence_gte: 0.9 }
          confirm: { confidence_gte: 0.5 }
          escalate: { otherwise: true }
        """;

    /** A payment_risk decision cleared by the rule, as the live runtime records it, then its real outcome. */
    private void clearedThenOutcome(int version, String note, boolean fraud, int ageDays) {
        String orderId = "ord_" + Ids.shortId();
        Map<String, Object> input = Map.of("order", Map.of("id", orderId, "total", 120.0, "channel", "web"),
            "payment", Map.of("velocity_1h", 1, "note", note));
        Instant at = Instant.now().minus(Duration.ofDays(ageDays));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tier", "T2");
        details.put("mode", "LIVE");
        records.insert(new DecisionRecord(Ids.id(), "payment_risk", version, Mode.LIVE, "order", orderId, orderId, Layer.L0_RULE, Band.ACT, "act", false, Map.<String, Answer>of(),
            1.0, null, null, "hash_" + orderId, Map.of(), input, List.of("clean_velocity"), List.of("clean_velocity"), 1, 0, 0, false, null, "system:order-workflow", details,
            null, null, null, null, Map.of(), at, Map.of(), List.of("true", "false"), "false", 1.0, version));
        Instant delivered = at.plus(Duration.ofDays(4));
        Event e = fraud
            ? new Event("evt-" + orderId + "-cb", tenant, "order", orderId, 0, "payment.chargeback", Map.of("orderId", orderId, "fraud", true, "amount", 120.0, "reasonCode", "fraudulent"),
                Actor.system("stripe-webhook"), null, orderId, Map.of(), delivered.plus(Duration.ofDays(20)))
            : new Event("evt-" + orderId + "-del", tenant, "order", orderId, 0, "order.delivered", Map.of("orderId", orderId, "onTime", true),
                Actor.system("carrier-webhook"), null, orderId, Map.of(), delivered);
        fraudLabels.on(e);
    }

    @Test
    void aClearanceRuleTheOutcomesProveWrongIsProposedForRetirement() {
        var d = studio.createDraft("payment_risk", YAML);
        studio.publish("payment_risk", d.version(), "live", true);
        int v = d.version();
        // 20 cleared orders with stolen-card signals came back as fraud chargebacks; 10 ordinary ones were delivered undisputed.
        for (int i = 0; i < 20; i++) clearedThenOutcome(v, "stolen card signals on the payment", true, 130 + i);
        for (int i = 0; i < 10; i++) clearedThenOutcome(v, "regular repeat customer", false, 135 + i);
        // A recent fraud is not used: its cohort's goods are not settled yet.
        clearedThenOutcome(v, "stolen card signals on the payment", true, 5);

        List<DecisionRecord> live = records.labelledBetween("payment_risk", v, Instant.now().minus(Duration.ofDays(400)), Instant.now());
        assertThat(live).hasSize(31).allSatisfy(r -> {
            assertThat(r.layer()).isEqualTo(Layer.L0_RULE);
            assertThat(r.requiresHuman()).isFalse();
            assertThat(r.labels()).containsKey(FraudLabels.LABEL).doesNotContainKey("human_resolution");
        });

        ruleUtilization.sweepOnce();

        Proposal p = proposals.byStatus("DRAFT", 200).stream().filter(x -> x.changeset().target().toString().equals("decision:payment_risk")).findFirst().orElseThrow();
        assertThat(p.createdBy()).isEqualTo("system:rule_utilization");
        assertThat(p.changeset().changes()).singleElement().isInstanceOfSatisfying(PolicyChange.RuleRetire.class, rr -> assertThat(rr.ruleId()).isEqualTo("clean_velocity"));
        assertThat(p.changeset().motivatedBy()).contains("the model is right on 100% of 30 cases").contains("30 fraud outcomes").contains("the rule on 33%");
    }

    @Test
    void aRuleTheOutcomesAgreeWithIsKept() {
        var d = studio.createDraft("payment_risk", YAML);
        studio.publish("payment_risk", d.version(), "live", true);
        int v = d.version();
        // Cleared orders that really were good (and a model that would have held some of them on its own).
        for (int i = 0; i < 12; i++) clearedThenOutcome(v, i % 2 == 0 ? "stolen card signals on the payment" : "regular repeat customer", false, 130 + i);
        ruleUtilization.sweepOnce();
        assertThat(proposals.byStatus("DRAFT", 200).stream().filter(x -> x.changeset().target().toString().equals("decision:payment_risk"))).isEmpty();
    }
}
