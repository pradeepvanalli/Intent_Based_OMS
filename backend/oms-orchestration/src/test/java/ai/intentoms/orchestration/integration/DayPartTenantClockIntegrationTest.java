package ai.intentoms.orchestration.integration;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.finance.model.Money;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.learner.FulfilmentFacts;
import ai.intentoms.learning.learner.NodeAssignmentRepository;
import ai.intentoms.learning.learner.NodeReliabilityLearner;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.DayPart;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * FS-02 / audit defect B-3: one day-part definition on the tenant's clock. An order placed at 06:17 UTC by a New York
 * tenant is placed at 02:17 local time: the sourcing decision stamps {@code night} (UTC would say {@code morning}),
 * reads the node facts filed under {@code night}, and the learner files the assignment under {@code night} too.
 */
class DayPartTenantClockIntegrationTest extends OrchestrationTestBase {
    private static final Instant AT = Instant.parse("2026-10-19T06:17:00Z");

    @Autowired DecisionRecordRepository records;
    @Autowired FulfilmentFacts facts;
    @Autowired LearnedFactCache factCache;
    @Autowired ParameterResolver parameters;
    @Autowired LearnerGovernance governance;
    @Autowired NodeAssignmentRepository assignments;

    @AfterEach
    void revoke() {
        parameters.revoke(NodeReliabilityFeatures.ON_TIME_RATE, "test");
        factCache.invalidateAll();
    }

    private Order orderToNewYork() {
        String ref = "NYC-" + Ids.shortId();
        Order o = capture(new CaptureRequest("web", ref, "standard_ship", "cust-nyc", customer("cust-nyc"), List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Sam Ortiz", "350 5th Ave", null, "New York", "NY", "10118", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd("80.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        return o;
    }

    private DecisionRecord sourcingRecord(Order o) {
        return records.forSubject("order", o.id()).stream().filter(r -> "sourcing_node_score".equals(r.decision())).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOrderAt0617UtcToNewYorkReadsTheNightFacts() {
        assertThat(DayPart.of(AT, ZoneOffset.UTC)).isEqualTo("morning");
        config.set(DayPart.TIMEZONE_KEY, Json.write("America/New_York"));
        time.override(Clock.fixed(AT, ZoneOffset.UTC));
        governance.setTenantKillSwitch(false, "user:tester");
        governance.setEnabled(NodeReliabilityLearner.KEY, true, "user:tester");
        governance.resume(NodeReliabilityLearner.KEY, "user:tester");
        governance.setAutonomy(NodeReliabilityLearner.KEY, AutonomyLevel.L1_RECOMMEND, "user:tester");

        // A first order shows which nodes and SKU class the decision considers.
        DecisionRecord probe = sourcingRecord(orderToNewYork());
        assertThat(Json.path(probe.input(), "order.day_part")).isEqualTo("night");
        String skuClass = String.valueOf(Json.path(probe.input(), "order.sku_class"));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) probe.input().get("candidates");
        assertThat(candidates).isNotEmpty();

        // What each node has learned at night and in the morning differs; the decision must read the night facts.
        for (Map<String, Object> c : candidates) {
            String node = String.valueOf(c.get("id"));
            facts.number(SubjectType.NODE, NodeReliabilityLearner.subject(node, skuClass, "night"), FactKey.NODE_ON_TIME_RATE, 0.41, 50, NodeReliabilityLearner.KEY, 0.0);
            facts.number(SubjectType.NODE, NodeReliabilityLearner.subject(node, skuClass, "morning"), FactKey.NODE_ON_TIME_RATE, 0.97, 50, NodeReliabilityLearner.KEY, 0.0);
        }
        parameters.promote(NodeReliabilityFeatures.ON_TIME_RATE, NodeReliabilityLearner.KEY, 0.0, 1.0, null, null, "user:tester");
        factCache.invalidateAll();

        Order o = orderToNewYork();
        DecisionRecord rec = sourcingRecord(o);
        assertThat(Json.path(rec.input(), "order.day_part")).isEqualTo("night");
        List<Map<String, Object>> used = (List<Map<String, Object>>) rec.details().get("learnedFacts");
        assertThat(used).filteredOn(u -> "NODE_ON_TIME_RATE".equals(u.get("factKey"))).isNotEmpty()
            .allSatisfy(u -> assertThat(String.valueOf(u.get("subjectId"))).endsWith("|" + skuClass + "|night"));
        for (Map<String, Object> c : (List<Map<String, Object>>) rec.input().get("candidates")) {
            assertThat(((Number) c.get("on_time_rate")).doubleValue()).isCloseTo(0.41, within(1e-9));
        }

        // The learner files the same order under the same day part.
        drainOutbox();
        assertThat(assignments.assignmentsFor(o.id())).isNotEmpty().allSatisfy(a -> assertThat(a.dayPart()).isEqualTo("night"));
    }
}
