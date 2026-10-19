package ai.intentoms.orchestration.fraud;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.finance.model.Money;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.FraudFactFeatures;
import ai.intentoms.learning.learner.FraudLabelCapture;
import ai.intentoms.learning.learner.FraudLabels;
import ai.intentoms.learning.learner.FraudSignalLearner;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.TaskRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * FD-01 learned signal features, FD-05 (reputation stays out of decisions), FD-06 label capture from human review,
 * return grading and per-group delivery, and defect 12 (provisional labels wait out the chargeback window), through the
 * real Spring context and database.
 */
class FraudLabelsAndSignalsIntegrationTest extends OrchestrationTestBase {
    @Autowired DecisionRecordRepository records;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired FraudSignalLearner signals;
    @Autowired FraudLabelCapture labels;
    @Autowired LearnerGovernance governance;
    @Autowired ParameterResolver parameters;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache factCache;
    @Autowired DecisionLearningHooks hooks;
    @Autowired PaymentRiskGate gate;

    @AfterEach
    void revoke() {
        parameters.revoke(FraudFactFeatures.SIGNAL_RISK, "test");
        parameters.revoke("fraud.agent_reputation", "test");
        factCache.invalidateAll();
    }

    private DecisionRecord record(String decision, String orderId, Map<String, Object> input, Instant at) {
        int version = definitions.byStatus(decision, "live").isEmpty() ? 1 : definitions.byStatus(decision, "live").get(0).definition().version();
        String id = Ids.prefixed("dr");
        DecisionRecord r = new DecisionRecord(id, decision, version, Mode.LIVE, "order", orderId, orderId, Layer.L1_SYSTEM_ONE, Band.ACT, "act", false, Map.of(), 0.9,
            "test", "t-1", Json.sha256(input), input, input, List.of(), List.of(), 0, 0, 0, false, null, "system:test", Map.of("mode", "LIVE"), null, null, null, null,
            Map.of(), at, input, List.of("true", "false"), "false", 1.0, version);
        records.insert(r);
        return r;
    }

    private static Map<String, Object> input(int velocity24h, String agentId) {
        Map<String, Object> payment = Map.of("velocity_1h", 0, "velocity_24h", velocity24h, "address_linkage_count", 0, "device_linkage_count", 0, "token_customer_count", 0,
            "billing_shipping_mismatch", false);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("payment", payment);
        in.put("order", Map.of("total", 120.0, "channel", "web"));
        in.put("agent", agentId == null ? Map.of("mandate_present", false) : Map.of("id", agentId, "mandate_present", true));
        return in;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> label(String recordId) {
        return (Map<String, Object>) records.find(recordId).orElseThrow().labels().get(FraudLabels.LABEL);
    }

    private void learnerOn(String key) {
        governance.setTenantKillSwitch(false, "user:tester");
        governance.setEnabled(key, true, "user:tester");
        governance.resume(key, "user:tester");
        governance.setAutonomy(key, AutonomyLevel.L1_RECOMMEND, "user:tester");
    }

    private double rate(SubjectType st, String subject) {
        return ((TypedValue.NumberValue) facts.active(st, subject, FactKey.FRAUD_SIGNAL_RATE).orElseThrow().value()).value();
    }

    /** FD-01: what each velocity level and an agent's mandated orders have meant, learned from confirmed outcomes and served once promoted. */
    @Test
    @SuppressWarnings("unchecked")
    void signalLevelsAndAgentHistoryBecomeLearnedFeaturesServedOnlyWhenPromoted() {
        learnerOn(FraudSignalLearner.KEY);
        String agent = "agt-" + Ids.shortId();
        Instant at = Instant.now().minus(Duration.ofDays(3));
        // 20 orders with no extra cards (1 fraud) and 20 with 2-3 extra cards in a day (10 fraud); 10 of the latter by one agent (6 fraud).
        int fraudAtAgent = 0;
        for (int i = 0; i < 40; i++) {
            boolean busy = i >= 20;
            boolean byAgent = i >= 30;
            boolean fraud = busy ? (byAgent ? (fraudAtAgent++ < 6) : i < 24) : i == 0;
            DecisionRecord r = record("payment_risk", "ord_" + Ids.shortId(), input(busy ? 3 : 0, byAgent ? agent : null), at);
            records.label(r.id(), FraudLabels.LABEL, Map.of("confirmed", fraud, "kind", fraud ? "chargeback_fraud" : "chargeback_won", "provisional", false));
        }
        // Delivered last week, no chargeback yet: provisional, inside the window, so not counted (defect 12).
        for (int i = 0; i < 10; i++) {
            DecisionRecord r = record("payment_risk", "ord_" + Ids.shortId(), input(3, null), Instant.now().minus(Duration.ofDays(7)));
            records.label(r.id(), FraudLabels.LABEL, Map.of("confirmed", false, "kind", "delivered_no_chargeback", "provisional", true));
        }
        signals.learnTenant();

        double base = (11 + 1.0) / (40 + 2.0);
        assertThat(rate(SubjectType.DECISION, FraudSignalLearner.subject("velocity_24h", "2-3"))).isCloseTo((10 + 10 * base) / (20 + 10), within(1e-3));
        assertThat(rate(SubjectType.DECISION, FraudSignalLearner.subject("velocity_24h", "0"))).isCloseTo((1 + 10 * base) / (20 + 10), within(1e-3));
        LearnedFact agentHistory = facts.active(SubjectType.AGENT, agent, FactKey.FRAUD_SIGNAL_RATE).orElseThrow();
        assertThat(agentHistory.sampleSize()).isEqualTo(10);
        assertThat(((TypedValue.NumberValue) agentHistory.value()).value()).isCloseTo((6 + 10 * base) / (10 + 10), within(1e-3));

        // Not promoted: listed on the record as considered, not served.
        factCache.invalidateAll();
        DecisionOptions opts = DecisionOptions.live("order", "ord_probe", Actor.system("test"));
        var before = hooks.contribute("payment_risk", input(3, agent), opts);
        assertThat(Json.path(before.input(), "payment.learned_fraud_rate")).isNull();
        assertThat(before.factsUsed()).anySatisfy(u -> assertThat(u).containsEntry("source", "STATIC").containsEntry("factKey", "FRAUD_SIGNAL_RATE"));

        parameters.promote(FraudFactFeatures.SIGNAL_RISK, FraudSignalLearner.KEY, 0.0, 1.0, null, null, "user:tester");
        // An agent reputation promoted the old way still never reaches the decision (FD-05: manual tier review only).
        facts.supersede(SubjectType.AGENT, agent, FactKey.AGENT_REPUTATION, Instant.now());
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.AGENT, agent, FactKey.AGENT_REPUTATION, new TypedValue.NumberValue(0.2), 0.8, 10, "agent_reputation", 1,
            Instant.now(), null, Instant.now()));
        parameters.promote("fraud.agent_reputation", "agent_reputation", 0.0, 1.0, null, null, "user:tester");
        factCache.invalidateAll();
        var after = hooks.contribute("payment_risk", input(3, agent), opts);
        Map<String, Object> rates = (Map<String, Object>) Json.path(after.input(), "payment.learned_fraud_rates");
        assertThat(((Number) rates.get("velocity_24h")).doubleValue()).isCloseTo((10 + 10 * base) / (20 + 10), within(1e-3));
        assertThat(((Number) Json.path(after.input(), "payment.learned_fraud_rate")).doubleValue()).isGreaterThanOrEqualTo(((Number) rates.get("velocity_24h")).doubleValue());
        assertThat(((Number) Json.path(after.input(), "agent.history_fraud_rate")).doubleValue()).isCloseTo((6 + 10 * base) / (10 + 10), within(1e-3));
        assertThat(Json.path(after.input(), "agent.history_orders")).isEqualTo(10);
        assertThat(Json.path(after.input(), "agent.reputation_score")).isNull();

        // A real order through the gate: the payment_risk record carries the learned features and the definition reads them.
        Order o = capture(new CaptureRequest("web", "FD01-" + Ids.shortId(), "standard_ship", "cust-fd01", customer("cust-fd01"), List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Dana Whitfield", "233 S Wacker Dr", null, "Chicago", "IL", "60606", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_fd01", Money.usd("80.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null));
        Map<String, Object> assessed = (Map<String, Object>) gate.assess(o).get("payment_risk");
        DecisionRecord live = records.find(String.valueOf(assessed.get("recordId"))).orElseThrow();
        assertThat(Json.path(live.input(), "payment.learned_fraud_rates.velocity_24h")).isNotNull();
        assertThat(Json.path(live.state(), "payment.learned_fraud_rate")).isNotNull();
    }

    /** FD-06: a reviewer's answer on a fraud decision's escalation task becomes a fraud label. */
    @Test
    void humanReviewAnswersBecomeFraudLabels() {
        String order1 = "ord_" + Ids.shortId(), order2 = "ord_" + Ids.shortId();
        DecisionRecord risky = record("payment_risk", order1, input(3, null), Instant.now());
        DecisionRecord cleared = record("payment_risk", order2, input(0, null), Instant.now());

        HumanTask t1 = tasks.create(new TaskRequest("decision_escalation", "risk_review", "order", order1, "Decision payment_risk needs a human",
            Map.of("decision", "payment_risk", "recordId", risky.id(), "recommendedValue", true), null, Set.of(), 4, null));
        tasks.complete(t1.id(), "override", "stolen card confirmed with the issuer", Map.of("value", true));
        HumanTask t2 = tasks.create(new TaskRequest("decision_escalation", "risk_review", "order", order2, "Decision payment_risk needs a human",
            Map.of("decision", "payment_risk", "recordId", cleared.id(), "recommendedValue", true), null, Set.of(), 4, null));
        tasks.complete(t2.id(), "override", "customer verified by phone", Map.of("value", false));
        drainOutbox();

        assertThat(label(risky.id())).containsEntry("confirmed", true).containsEntry("kind", "human_review").containsEntry("provisional", false)
            .containsEntry("reasonCode", "stolen card confirmed with the issuer");
        // "Not risky" from a reviewer is provisional: a chargeback can still prove otherwise.
        assertThat(label(cleared.id())).containsEntry("confirmed", false).containsEntry("kind", "human_review").containsEntry("provisional", true);
        assertThat(FraudLabels.usable(records.find(cleared.id()).orElseThrow(), Instant.now(), 120)).isNull();
        assertThat(FraudLabels.usable(records.find(risky.id()).orElseThrow(), Instant.now(), 120)).isTrue();
    }

    /** FD-06: return grading labels return_risk; delivery labels wait for the whole order and only the payment label is provisional. */
    @Test
    void returnGradingAndDeliveryLabelTheRightDecisions() {
        String order = "ord_" + Ids.shortId();
        DecisionRecord returnRisk = record("return_risk", order, Map.of("return", Map.of("reason", "changed_mind")), Instant.now());
        DecisionRecord payment = record("payment_risk", order, input(0, null), Instant.now());
        DecisionRecord rto = record("rto_risk", order, Map.of("order", Map.of("payment_method", "cod")), Instant.now());

        labels.on(new Event(Ids.id(), tenant, "return", "rma_" + order, 1, "return.dispositioned", Map.of("disposition", "liquidate", "condition", "wrong_item"),
            Actor.system("returns"), null, order, Map.of("orderId", order), Instant.now()));
        assertThat(label(returnRisk.id())).containsEntry("confirmed", true).containsEntry("kind", "return_grading");

        labels.on(new Event(Ids.id(), tenant, "order", order, 2, "order.delivered", Map.of("orderId", order, "fulfilmentGroupId", "fg1", "groupDelivered", true, "allDelivered", false),
            Actor.system("carrier"), null, order, Map.of(), Instant.now()));
        assertThat(label(payment.id())).isNull();
        labels.on(new Event(Ids.id(), tenant, "order", order, 3, "order.delivered", Map.of("orderId", order, "fulfilmentGroupId", "fg2", "groupDelivered", true, "allDelivered", true),
            Actor.system("carrier"), null, order, Map.of(), Instant.now()));
        assertThat(label(payment.id())).containsEntry("confirmed", false).containsEntry("provisional", true);
        assertThat(label(rto.id())).containsEntry("confirmed", false).containsEntry("provisional", false);
    }
}
