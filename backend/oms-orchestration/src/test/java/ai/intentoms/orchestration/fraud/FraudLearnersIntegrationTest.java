package ai.intentoms.orchestration.fraud;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.finance.model.Money;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.FraudFactFeatures;
import ai.intentoms.learning.learner.FraudAbuseHypothesisScanner;
import ai.intentoms.learning.learner.FraudDriftMonitor;
import ai.intentoms.learning.learner.FraudThresholdTuner;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * FD-03 / FD-04 / FD-07 through the real Spring context and database: the threshold tuner proposes a T2 threshold
 * change with its evidence, the abuse scanner finds an address shared by several customers and (once promoted for live
 * use) that count reaches the next payment-risk decision, and a velocity spike pauses the fraud learners.
 */
class FraudLearnersIntegrationTest extends OrchestrationTestBase {
    @Autowired DecisionRecordRepository records;
    @Autowired FraudThresholdTuner tuner;
    @Autowired FraudAbuseHypothesisScanner scanner;
    @Autowired FraudDriftMonitor drift;
    @Autowired ProposalRepository proposals;
    @Autowired HypothesisRepository hypotheses;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnerGovernance governance;
    @Autowired ParameterResolver parameters;
    @Autowired PaymentRiskGate gate;
    @Autowired ai.intentoms.learning.store.LearnedFactCache factCache;
    @Autowired ai.intentoms.decision.definition.DecisionDefinitionRepository definitions;

    private int liveVersion() { return definitions.byStatus("payment_risk", "live").get(0).definition().version(); }

    private DecisionRecord paymentRisk(boolean saidRisky, double confidence, Map<String, Object> input, Instant at) {
        String id = Ids.prefixed("dr");
        return new DecisionRecord(id, "payment_risk", liveVersion(), Mode.LIVE, "order", "ord_" + id, "ord_" + id, Layer.L1_SYSTEM_ONE, Band.ACT, "act", saidRisky, Map.of(), confidence,
            "test", "t-1", Json.sha256(input), input, input, List.of(), List.of(), 0, 0, 0, false, null, "system:test", Map.of("mode", "LIVE"), null, null, null, null,
            Map.of(), at, input, List.of("true", "false"), String.valueOf(saidRisky), 1.0, 2);
    }

    private static Map<String, Object> input(int velocity1h, double total) {
        return Map.of("payment", Map.of("velocity_1h", velocity1h, "velocity_24h", 0, "address_linkage_count", 0, "device_linkage_count", 0, "token_customer_count", 0,
            "billing_shipping_mismatch", false), "order", Map.of("total", total, "channel", "web"));
    }

    /**
     * Fraud is confirmed by a chargeback; "not fraud" is the provisional delivered label, matured past the 120-day window.
     * FD-03: frauds and goods come from the same cohort, decisions old enough that the window has closed.
     */
    private void labelled(boolean saidRisky, double confidence, boolean fraud, int n) {
        for (int i = 0; i < n; i++) {
            DecisionRecord r = paymentRisk(saidRisky, confidence, input(0, 120.0), Instant.now().minus(Duration.ofDays(130)));
            records.insert(r);
            records.label(r.id(), "fraud_outcome", Map.of("confirmed", fraud, "kind", fraud ? "chargeback_fraud" : "delivered_no_chargeback", "provisional", !fraud));
        }
    }

    /** FD-03: a chargeback on last week's order is real, but its goods are not known yet; counting it alone would bias the sample. */
    private void recentFraud(int n) {
        for (int i = 0; i < n; i++) {
            DecisionRecord r = paymentRisk(false, 0.95, input(0, 120.0), Instant.now().minus(Duration.ofDays(5)));
            records.insert(r);
            records.label(r.id(), "fraud_outcome", Map.of("confirmed", true, "kind", "chargeback_fraud", "provisional", false));
        }
    }

    /** Defect 12: delivered last week, no chargeback yet: not ground truth until the chargeback window has passed. */
    private void freshProvisional(boolean saidRisky, int n) {
        for (int i = 0; i < n; i++) {
            DecisionRecord r = paymentRisk(saidRisky, 0.95, input(0, 120.0), Instant.now().minus(Duration.ofDays(7)));
            records.insert(r);
            records.label(r.id(), "fraud_outcome", Map.of("confirmed", false, "kind", "delivered_no_chargeback", "provisional", true));
        }
    }

    @Test
    void thresholdTunerProposesAThresholdThatMeetsTheFalsePositiveTarget() {
        labelled(true, 0.92, false, 10);   // good orders the model wrongly calls risky
        labelled(false, 0.95, false, 15);  // good orders it gets right
        labelled(true, 0.97, true, 5);     // fraud it catches
        freshProvisional(true, 10);        // still inside the chargeback window: not counted
        recentFraud(6);                    // nor the frauds of that young cohort (same observation window for both)

        tuner.runNow();

        Proposal p = proposals.byStatus("DRAFT", 100).stream().filter(x -> "decision:payment_risk".equals(x.changeset().target().toString())).findFirst().orElseThrow();
        assertThat(p.createdBy()).isEqualTo("system:" + FraudThresholdTuner.KEY);
        assertThat(p.tier().name()).isEqualTo("T2");
        PolicyChange.ThresholdChange tc = (PolicyChange.ThresholdChange) p.changeset().changes().get(0);
        assertThat(tc.from()).isEqualTo(0.9);
        assertThat(tc.to()).isCloseTo(0.93, within(1e-9));
        Hypothesis h = hypotheses.find(p.hypothesisId()).orElseThrow();
        assertThat(h.evidence()).anySatisfy(e -> assertThat(e).contains("labelled_cases=30"));
        assertThat(facts.active(SubjectType.DECISION, "payment_risk", FactKey.FALSE_POSITIVE_RATE)).hasValueSatisfying(f ->
            assertThat(((TypedValue.NumberValue) f.value()).value()).isCloseTo(0.4, within(1e-9)));

        tuner.runNow(); // one open proposal per decision
        assertThat(proposals.byStatus("DRAFT", 100).stream().filter(x -> "decision:payment_risk".equals(x.changeset().target().toString()))).hasSize(1);
    }

    private CaptureRequest atSharedAddress(String channelOrderId, String customer) {
        return new CaptureRequest("web", channelOrderId, "standard_ship", customer, customer(customer), List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Drop Point", "900 W Fulton Mkt", "Unit 7", "Chicago", "IL", "60607", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_" + customer, Money.usd("80.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void abuseScannerFindsTheRingAndThePromotedCountReachesTheNextDecision() {
        String token = null;
        for (int i = 1; i <= 3; i++) {
            Order o = capture(atSharedAddress("RING-" + i, "ring-cust-" + i));
            gate.assess(o);
            token = AddressTokens.token(o.shipTo().orElseThrow());
        }
        final String ringToken = token;

        scanner.runNow();
        assertThat(facts.active(SubjectType.ADDRESS, ringToken, FactKey.ADDRESS_REUSE_COUNT)).hasValueSatisfying(f ->
            assertThat(((TypedValue.NumberValue) f.value()).value()).isEqualTo(3.0));
        List<Hypothesis> rings = hypotheses.byStatus("OPEN", 100).stream().filter(h -> ringToken.equals(h.scope().get("addressToken"))).toList();
        assertThat(rings).singleElement().satisfies(h -> assertThat(h.evidence()).contains("distinct_customers=3"));
        scanner.runNow();
        assertThat(hypotheses.byStatus("OPEN", 100).stream().filter(h -> ringToken.equals(h.scope().get("addressToken")))).hasSize(1);

        // Not promoted yet: the decision lists the fact as considered but does not use it. (Live reads go through a
        // 15-second cache, which cached "no fact" during the first three orders; production simply waits it out.)
        factCache.invalidateAll();
        Order before = capture(atSharedAddress("RING-4", "ring-cust-4"));
        Map<String, Object> r1 = (Map<String, Object>) gate.assess(before).get("payment_risk");
        DecisionRecord rec1 = records.find(String.valueOf(r1.get("recordId"))).orElseThrow();
        assertThat(Json.path(rec1.input(), "payment.address_reuse_count")).isNull();
        assertThat((List<Map<String, Object>>) rec1.details().get("learnedFacts")).anySatisfy(u -> assertThat(u).containsEntry("factKey", "ADDRESS_REUSE_COUNT").containsEntry("source", "STATIC"));

        // Promoted for live use: the next decision reads the learned count.
        parameters.promote(FraudFactFeatures.ADDRESS_REUSE, FraudAbuseHypothesisScanner.KEY, 0.0, 1000.0, null, null, "test");
        Order after = capture(atSharedAddress("RING-5", "ring-cust-5"));
        Map<String, Object> r2 = (Map<String, Object>) gate.assess(after).get("payment_risk");
        DecisionRecord rec2 = records.find(String.valueOf(r2.get("recordId"))).orElseThrow();
        assertThat(Json.path(rec2.input(), "payment.address_reuse_count")).isEqualTo(3);
        assertThat((List<Map<String, Object>>) rec2.details().get("learnedFacts")).anySatisfy(u -> assertThat(u).containsEntry("factKey", "ADDRESS_REUSE_COUNT").containsEntry("source", "LEARNED"));
        assertThat(rec2.rulesFired()).isEmpty();
        assertThat(r2).containsEntry("flagged", true);
    }

    @Test
    void velocitySpikePausesTheFraudLearners() {
        for (int i = 0; i < 60; i++) records.insert(paymentRisk(false, 1.0, input(0, 90.0), Instant.now().minus(Duration.ofDays(14))));
        for (int i = 0; i < 60; i++) records.insert(paymentRisk(false, 1.0, input(i % 2 == 0 ? 3 : 0, 90.0), Instant.now().minus(Duration.ofDays(1))));

        FraudDriftMonitor.DriftReport report = drift.checkTenant();

        assertThat(report.featurePsi().get("payment.velocity_1h")).isGreaterThan(0.25);
        assertThat(report.pausedFor()).contains("payment.velocity_1h");
        assertThat(governance.gate(FraudThresholdTuner.KEY).mayRun()).isFalse();
        assertThat(governance.gate(FraudThresholdTuner.KEY).reason()).startsWith("paused: fraud feature drift");
        assertThat(governance.gate(FraudAbuseHypothesisScanner.KEY).mayRun()).isFalse();
        assertThat(facts.active(SubjectType.DECISION, "payment_risk", FactKey.FEATURE_DRIFT_PSI)).isPresent();
        // FD-07: an operator alert, not just a log line.
        var alert = jdbc.sql("select severity, title, body from alert where tenant_id = :t and key = :k").param("t", tenant).param("k", "learning:fraud_drift:" + tenant)
            .query().listOfRows();
        assertThat(alert).hasSize(1);
        assertThat(alert.get(0)).containsEntry("severity", "high");
        assertThat(String.valueOf(alert.get(0).get("body"))).contains("payment.velocity_1h").contains(FraudThresholdTuner.KEY);
    }
}
