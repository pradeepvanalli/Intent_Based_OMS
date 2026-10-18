package ai.intentoms.orchestration.cx;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.AppeasementLevelLearner;
import ai.intentoms.learning.learner.FulfilmentFacts;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.order.model.Order;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CX-03 proactive delay notice and CX-04 appeasement recommendation, driven through the real outbox relay. */
class CxSubscribersIntegrationTest extends OrchestrationTestBase {
    @Autowired EventStore events;
    @Autowired DecisionService decisions;
    @Autowired ComplaintAppeasementSubscriber appeasement;
    @Autowired FulfilmentFacts facts;
    @Autowired LearnedFactCache factCache;
    @Autowired ParameterResolver parameters;

    private List<DecisionRecord> appeasements(String orderId) {
        return decisions.forSubject("order", orderId).stream().filter(r -> ComplaintAppeasementSubscriber.DECISION.equals(r.decision())).toList();
    }

    private List<Map<String, Object>> eventPayloads(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_type = 'order' and aggregate_id = :o and type = :type order by sequence")
            .param("t", tenant).param("o", orderId).param("type", type).query(String.class).list().stream().map(Json::readMap).toList();
    }

    @SuppressWarnings("unchecked")
    private static double credit(DecisionRecord r) { return ((Number) ((Map<String, Object>) r.input().get("request")).get("credit_usd")).doubleValue(); }

    @Test
    void complaintGetsAnAppeasementRecommendationAtTheStaticThenLearnedLevel() {
        Order o = capture(webOrder("cx-" + Ids.shortId(), "tok_ok"));
        events.append("order", o.id(), new NewEvent("cs.complaint", Map.of("orderId", o.id(), "issue", "late_delivery", "severity", "high"), Actor.user("cs")));
        drainOutbox();
        List<DecisionRecord> first = appeasements(o.id());
        assertThat(first).hasSize(1);
        assertThat(credit(first.get(0))).isEqualTo(10.0);
        assertThat(eventPayloads(o.id(), ComplaintAppeasementSubscriber.EVT_RECOMMENDED)).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("creditSource", "STATIC").containsEntry("segment", "tier:gold"));

        // A wismo contact that is not a complaint does nothing; a redelivered complaint does nothing.
        events.append("order", o.id(), new NewEvent("cs.wismo", Map.of("orderId", o.id(), "channel", "email"), Actor.user("cs")));
        drainOutbox();
        assertThat(appeasements(o.id())).hasSize(1);

        facts.number(SubjectType.SEGMENT, "tier:gold", FactKey.APPEASEMENT_LEVEL, 20, 40, AppeasementLevelLearner.KEY, 0.5);
        factCache.invalidateAll();
        parameters.promote(AppeasementLevelLearner.PARAMETER, AppeasementLevelLearner.KEY, 0.0, 25.0, null, null, "test");
        Event second = events.append("order", o.id(), new NewEvent("cs.contact", Map.of("orderId", o.id(), "complaint", true, "issue", "damaged"), Actor.user("cs")));
        drainOutbox();
        appeasement.on(second);
        List<DecisionRecord> all = appeasements(o.id());
        assertThat(all).hasSize(2);
        assertThat(all.stream().mapToDouble(CxSubscribersIntegrationTest::credit).max().orElseThrow()).isEqualTo(20.0);
        assertThat(eventPayloads(o.id(), ComplaintAppeasementSubscriber.EVT_RECOMMENDED)).anyMatch(p -> "LEARNED".equals(p.get("creditSource")));
    }

    @Test
    void delayWarningRepromisesOrWarnsTheCustomer() {
        Order o = capture(webOrder("cx-" + Ids.shortId(), "tok_ok"));
        events.append("order", o.id(), new NewEvent("order.delay_warning", Map.of("score", 3, "threshold", 2.0, "thresholdSource", "LEARNED"), Actor.system("risk-scorer")));
        drainOutbox();
        List<String> templates = jdbc.sql("select template from customer_communication where tenant_id = :t and order_id = :o").param("t", tenant).param("o", o.id()).query(String.class).list();
        boolean repromised = !eventPayloads(o.id(), "order.repromised").isEmpty();
        assertThat(templates).as("customer heard about the delay risk either way").containsAnyOf(ProactiveDelayNoticeSubscriber.TEMPLATE, "delay_notice");
        if (!repromised) assertThat(templates).contains(ProactiveDelayNoticeSubscriber.TEMPLATE);
    }
}
