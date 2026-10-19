package ai.intentoms.monitoring.exception;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.finance.model.Money;
import ai.intentoms.inventory.learning.BackorderOutcomeLearner;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.BackorderFeatures;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.order.model.Customer;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.Totals;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * IU-06 end to end: an SLA breach on a backordered line runs {@code backorder_next_action}; when the line later
 * resolves, the outcome is linked to that decision and the option it chose; per-SKU and per-option kept rates are
 * learned; and once {@code inventory.backorder_conversion} is live the next decision reads them.
 */
class BackorderOutcomeLearningIntegrationTest extends MonitoringTestBase {
    @Autowired MonitoringEventSubscriber subscriber;
    @Autowired ExceptionService exceptions;
    @Autowired BackorderOutcomeLearner learner;
    @Autowired DecisionRecordRepository records;
    @Autowired LearnerGovernance governance;
    @Autowired ParameterResolver parameters;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache cache;

    String sku;

    @BeforeEach
    void learnerOn() {
        sku = "BO-" + Ids.shortId();
        governance.setTenantKillSwitch(false, "user:tester");
        governance.setEnabled(BackorderOutcomeLearner.KEY, true, "user:tester");
        governance.resume(BackorderOutcomeLearner.KEY, "user:tester");
        governance.setAutonomy(BackorderOutcomeLearner.KEY, AutonomyLevel.L1_RECOMMEND, "user:tester");
        // The per-option facts are tenant-wide; start from a clean slate so earlier runs do not leak in.
        jdbc.sql("update inventory_backorder_resolution set chosen_option = null where tenant_id = :t").param("t", TENANT).update();
        cache.invalidateAll();
    }

    @AfterEach
    void revoke() {
        parameters.revoke(BackorderFeatures.PARAMETER, "test");
        cache.invalidateAll();
    }

    private Order backorderedOrder() {
        String id = "O" + Ids.shortId();
        OrderLine line = new OrderLine("L1", sku, "Widget", 1, Money.usd("25.00"), Money.usd("0"), Money.usd("0"), "fg_" + id, null, Map.of());
        FulfilmentGroup group = new FulfilmentGroup("fg_" + id, "DC-EAST", "ship_as_available", List.of(new FulfilmentGroup.Allocation("L1", sku, 1)), LocalDate.now().plusDays(3), 0.9,
            "ground", Money.usd("8.00"), "planned", null, null, Map.of());
        Totals totals = new Totals(Money.usd("25.00"), Money.usd("0"), Money.usd("8.00"), Money.usd("0"), Money.usd("0"), Money.usd("0"), Money.usd("33.00"));
        Order o = new Order(id, "web", "CH-" + id, "standard_ship", "cust-1", new Customer("cust-1", null, null, null, "gold", false, Map.of()), List.of(line), List.of(), List.of(), List.of(),
            List.of(), List.of(group), null, null, totals, null, "order.standard_ship", 1, "sourcing", "ground", "USD", Map.of(), Instant.now(), null, null, 0);
        return orders.insert(o);
    }

    private DecisionRecord breach(Order o) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityType", "order_line");
        p.put("entityId", o.id() + ":L1");
        p.put("state", "backordered");
        p.put("onBreach", "decision.backorder_next_action");
        p.put("enteredAt", Instant.now().minusSeconds(4 * 86_400).toString());
        p.put("dueAt", Instant.now().toString());
        p.put("ageSeconds", 4 * 86_400);
        p.put("timerId", "slt_" + o.id());
        subscriber.on(new Event("evt_" + o.id() + "_bo", TENANT, "order", o.id(), 7, "sla.breached", p, Actor.system("sla-sweeper"), null, null,
            Map.of("entityType", "order_line", "entityId", o.id() + ":L1"), Instant.now()));
        String recordId = exceptions.list(ExceptionQuery.forOrder(o.id())).items().get(0).decisionRecordId();
        return records.find(recordId).orElseThrow();
    }

    private void resolve(Order o, String to) {
        Map<String, Object> p = Map.of("entityType", "order_line", "entityId", o.id() + ":L1", "from", "backordered", "to", to, "trigger", "event.test");
        learner.on(new Event(Ids.id(), TENANT, "order", o.id(), 9, "order.transitioned", p, Actor.system("test"), null, o.id(), Map.of(), Instant.now().plusSeconds(5)));
    }

    private double number(SubjectType st, String id) {
        return ((TypedValue.NumberValue) facts.active(st, id, FactKey.BACKORDER_CONVERSION_RATE).orElseThrow().value()).value();
    }

    @Test
    void outcomesAreLinkedToTheChosenOptionAndTheNextDecisionReadsWhatWasLearned() {
        // 8 lines the decision (overridden here to pin the option) told to wait: 6 were cancelled. 6 re-sourced: all kept.
        for (int i = 0; i < 14; i++) {
            Order o = backorderedOrder();
            DecisionRecord d = breach(o);
            assertThat(d.subjectType()).isEqualTo("order");
            String option = i < 8 ? "wait" : "resource";
            records.override(d.id(), option, "pin the option for the test", "user:tester", Instant.now());
            resolve(o, i < 6 ? "cancelled" : "sourcing");

            Map<String, Object> row = jdbc.sql("select decision_record_id, chosen_option from inventory_backorder_resolution where tenant_id = :t and order_id = :o")
                .param("t", TENANT).param("o", o.id()).query().singleRow();
            assertThat(row).containsEntry("decision_record_id", d.id()).containsEntry("chosen_option", option);
            @SuppressWarnings("unchecked")
            Map<String, Object> label = (Map<String, Object>) records.find(d.id()).orElseThrow().labels().get("backorder_outcome");
            assertThat(label).containsEntry("chosenOption", option).containsEntry("outcome", i < 6 ? "cancelled" : "converted").containsEntry("sku", sku);
        }
        assertThat(number(SubjectType.SKU, sku)).isCloseTo((8 + 2.5) / (14 + 5), within(0.001));
        assertThat(number(SubjectType.DECISION, BackorderFeatures.optionSubject("wait"))).isCloseTo((2 + 2.5) / (8 + 5), within(0.001));
        assertThat(number(SubjectType.DECISION, BackorderFeatures.optionSubject("resource"))).isCloseTo((6 + 2.5) / (6 + 5), within(0.001));

        cache.invalidateAll(); // live paths read facts through a 15 s cache
        // Not promoted: the decision is told nothing, but the record lists the facts it could have used.
        DecisionRecord before = breach(backorderedOrder());
        assertThat(((Map<?, ?>) before.input().get("line")).containsKey("backorder_conversion_rate")).isFalse();
        @SuppressWarnings("unchecked") List<Map<String, Object>> consulted = (List<Map<String, Object>>) before.details().get("learnedFacts");
        assertThat(consulted).anySatisfy(u -> assertThat(u).containsEntry("subjectId", sku).containsEntry("source", "STATIC"));

        parameters.promote(BackorderFeatures.PARAMETER, BackorderOutcomeLearner.KEY, 0.0, 1.0, null, null, "user:tester");
        DecisionRecord after = breach(backorderedOrder());
        assertThat(((Number) ((Map<?, ?>) after.input().get("line")).get("backorder_conversion_rate")).doubleValue()).isCloseTo((8 + 2.5) / (14 + 5), within(0.001));
        Map<?, ?> kept = (Map<?, ?>) ((Map<?, ?>) after.input().get("history")).get("option_kept_rate");
        assertThat(((Number) kept.get("wait")).doubleValue()).isCloseTo(0.346, within(0.001));
        assertThat(((Number) kept.get("resource")).doubleValue()).isCloseTo(0.773, within(0.001));
        // The provider sees them too: the state include carries both paths.
        assertThat(((Map<?, ?>) after.state().get("line")).containsKey("backorder_conversion_rate")).isTrue();
        assertThat(after.state()).containsKey("history");
    }

    @Autowired ai.intentoms.platform.ledger.OutboxRelay outbox;
    @Autowired ai.intentoms.learning.store.EpisodeRepository episodes;

    /**
     * OC-02 on the live path (audit: no live episode had a line id, because a line had to stay backordered for days before
     * the SLA breach ran the decision): the line is decided as soon as it is backordered, on its own decision point; the
     * SLA breach later re-evaluates that same point, it does not open a second episode.
     */
    @Test
    void aLineIsDecidedOnItsOwnDecisionPointAsSoonAsItIsBackordered() {
        Order o = backorderedOrder();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityType", "order_line");
        p.put("entityId", o.id() + ":L1");
        p.put("from", "awaiting_sourcing");
        p.put("to", "backordered");
        p.put("trigger", "decision.sourcing_plan");
        subscriber.on(new Event("evt_" + o.id() + "_tr", TENANT, "order", o.id(), 5, "order.transitioned", p, Actor.system("workflow"), null, o.id(), Map.of(), Instant.now()));
        List<DecisionRecord> decided = records.forSubject("order", o.id()).stream().filter(r -> BackorderFeatures.DECISION.equals(r.decision())).toList();
        assertThat(decided).singleElement().satisfies(d -> {
            assertThat(ai.intentoms.learning.model.DecisionPoints.lineId(d)).isEqualTo("L1");
            assertThat(d.input()).containsEntry("orderId", o.id());
        });
        int guard = 0;
        while (outbox.drainBatch() > 0 && guard++ < 50) { /* deliver decision.evaluated to the episode assembler */ }
        ai.intentoms.platform.tenant.TenantContext.set(TENANT);
        var episode = episodes.findByDecisionId(decided.get(0).id()).orElseThrow();
        assertThat(episode.lineId()).isEqualTo("L1");
        assertThat(episode.orderId()).isEqualTo(o.id());

        DecisionRecord later = breach(o);
        guard = 0;
        while (outbox.drainBatch() > 0 && guard++ < 50) { }
        ai.intentoms.platform.tenant.TenantContext.set(TENANT);
        assertThat(episodes.find(episode.episodeId()).orElseThrow().lastDecisionId()).as("the breach re-evaluates the line's point").isEqualTo(later.id());
    }

    /** OC-02: each backordered line's decision is its own decision point, so its episode carries the line and the order. */
    @Test
    void theBackorderDecisionOpensTheLinesEpisode() {
        Order o = backorderedOrder();
        DecisionRecord d = breach(o);
        assertThat(ai.intentoms.learning.model.DecisionPoints.lineId(d)).isEqualTo("L1");
        int guard = 0;
        while (outbox.drainBatch() > 0 && guard++ < 50) { /* deliver decision.evaluated to the episode assembler */ }
        ai.intentoms.platform.tenant.TenantContext.set(TENANT);
        assertThat(episodes.findByDecisionId(d.id())).hasValueSatisfying(e -> {
            assertThat(e.lineId()).isEqualTo("L1");
            assertThat(e.orderId()).isEqualTo(o.id());
        });
    }
}
