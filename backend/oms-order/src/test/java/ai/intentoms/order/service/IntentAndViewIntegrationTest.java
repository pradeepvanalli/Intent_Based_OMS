package ai.intentoms.order.service;

import ai.intentoms.order.OrderTestBase;
import ai.intentoms.order.model.IntentProfile;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.model.OrderSummaryView;
import ai.intentoms.order.model.OrderTimelineEntry;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.testflows.TestStandardShipWorkflow;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.workflow.engine.WorkflowWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-CAP-06 intent inference with the heuristic provider and FR-CS-01 order view aggregation. */
class IntentAndViewIntegrationTest extends OrderTestBase {
    @Autowired OrderService orders;
    @Autowired IntentService intents;
    @Autowired HoldService holds;
    @Autowired WorkflowWorker worker;

    @Test
    void inferIntentRunsTheDecisionAndPersistsAProfile() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId(), "Please ship everything together in one delivery"), null);
        IntentProfile p = intents.inferIntent(o);
        assertThat(p.policy()).isIn("ship_complete", "ship_as_available");
        assertThat(p.source()).isIn("inferred", "channel_default");
        assertThat(p.decisionRecordId()).isNotBlank();
        if ("channel_default".equals(p.source())) assertThat(p.policy()).isEqualTo("ship_as_available"); // web default from order-types.yaml
        Order after = orders.get(o.id());
        assertThat(after.intent().policy()).isEqualTo(p.policy());
        assertThat(after.intent().decisionRecordId()).isEqualTo(p.decisionRecordId());
        assertThat(after.version()).isEqualTo(2);
        List<Map<String, Object>> resolved = events(o.id(), "order.intent_resolved");
        assertThat(resolved).hasSize(1);
        Map<String, Object> payload = Json.readMap((String) resolved.get(0).get("payload"));
        assertThat(payload).containsEntry("policy", p.policy()).containsEntry("decisionRecordId", p.decisionRecordId());
        // The decision record is attached to the order subject and shows in the view.
        OrderView view = orders.view(o.id());
        assertThat(view.decisionRecordIds()).contains(p.decisionRecordId());
        assertThat(view.decisions()).anyMatch(r -> "fulfilment_intent".equals(r.decision()));
        // Explicit intents are left alone.
        Order explicit = orders.capture(withIntentChoices(webOrder("W-" + Ids.shortId()), List.of("pickup_consolidated")), null);
        assertThat(intents.inferIntent(explicit).source()).isEqualTo("explicit");
        assertThat(events(explicit.id(), "order.intent_resolved")).isEmpty();
    }

    @Test
    void viewAggregatesStatusesHoldsPaymentsDecisionsWorkflowAndTimeline() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId(), "gift for my sister"), null);
        intents.inferIntent(o);
        holds.place("manual", "order", o.id(), Set.of("release"), "CS_REVIEW", "customer asked to wait", null, Actor.user("tester"));
        orders.addNote(o.id(), new Note("cs", "agent-1", "Called customer, confirmed address", null, false, null));
        worker.runOnce(); // completes the test workflow

        OrderView v = orders.view(o.id());
        assertThat(v.order().id()).isEqualTo(o.id());
        assertThat(v.order().notes()).hasSize(2);
        assertThat(v.header().state()).isEqualTo("created");
        assertThat(v.lineStatuses()).hasSize(2);
        assertThat(v.lineStatuses()).allMatch(s -> s.entityType().equals("order_line") && s.entityId().startsWith(o.id() + ":"));
        assertThat(v.holds()).singleElement().satisfies(h -> assertThat(h.reasonCode()).isEqualTo("CS_REVIEW"));
        assertThat(v.payments()).isEmpty();
        assertThat(v.refunds()).isEmpty();
        assertThat(v.shipments()).isEmpty();
        assertThat(v.returns()).isEmpty();
        assertThat(v.communications()).isEmpty();
        assertThat(v.risk()).isNull();
        assertThat(v.decisions()).isNotEmpty();
        assertThat(v.decisionRecordIds()).hasSameSizeAs(v.decisions());
        assertThat(v.workflow()).isNotNull();
        assertThat(v.workflow().type()).isEqualTo(TestStandardShipWorkflow.TYPE);
        assertThat(v.workflow().status()).isEqualTo("COMPLETED");
        assertThat(v.nextExpectedEvent()).isNull();
        assertThat(v.ledger()).extracting(l -> l.type()).contains("order.captured", "order.state_initialized", "hold.placed", "order.intent_resolved", "order.note_added");
        List<String> kinds = v.timeline().stream().map(OrderTimelineEntry::kind).distinct().toList();
        assertThat(kinds).contains("event", "decision", "workflow");
        assertThat(v.timeline()).extracting(OrderTimelineEntry::source).contains("ledger", "workflow", "decision");
        assertThat(v.timeline()).isSortedAccordingTo(java.util.Comparator.comparing(OrderTimelineEntry::at));
        assertThat(v.timeline()).anyMatch(t -> "order.captured".equals(t.name()) && "ledger".equals(t.source()));
        assertThat(v.timeline()).anyMatch(t -> "fulfilment_intent".equals(t.name()) && "decision".equals(t.source()));
        assertThat(orders.timeline(o.id())).hasSameSizeAs(v.timeline());

        Page<OrderSummaryView> page = orders.searchView(new OrderQuery(null, "web", null, "cust-jane", null, null, null, o.id(), null, null, 0, 10), null);
        assertThat(page.items()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(o.id());
            assertThat(s.held()).isTrue();
            assertThat(s.total().amount()).isEqualByComparingTo(o.totals().grandTotal().amount());
            assertThat(s.lineCount()).isEqualTo(2);
            assertThat(s.atRisk()).isFalse();
            assertThat(s.agentId()).isNull();
        });
        assertThat(orders.searchView(OrderQuery.all(0, 5), true).items()).isEmpty();
    }

    @Test
    void marginRecomputeAndApprovalAppendEvents() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
        Order recomputed = orders.recomputeMargin(o.id(), new BigDecimal("60.00"), new BigDecimal("5.00"), new BigDecimal("2.00"));
        assertThat(recomputed.margin().marginRatio()).isLessThan(o.margin().marginRatio());
        assertThat(recomputed.margin().belowFloor()).isTrue();
        assertThat(events(o.id(), "order.margin_recomputed")).hasSize(1);
        Order approved = orders.markBelowFloorApproval(o.id(), "task_1");
        assertThat(approved.margin().approvalPresent()).isTrue();
        assertThat(approved.margin().approvalTaskId()).isEqualTo("task_1");
        assertThat(approved.margin().belowFloor()).isTrue();
        assertThat(events(o.id(), "order.margin_approved")).hasSize(1);
        assertThat(approved.version()).isEqualTo(3);
    }
}
