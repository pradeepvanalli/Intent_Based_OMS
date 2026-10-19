package ai.intentoms.orchestration.workflow;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.fraud.PaymentRiskGate;
import ai.intentoms.orchestration.integration.LoggingCustomerNotifier;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The order events the self-optimizing layer learns from, through the real workflow (audit defects 7, 8, 10, 13, 14):
 * a parcel scan delivers only that parcel, a two-parcel order is scored once, a customer email never becomes a
 * learning subject id, one store reject re-sources once, and cash on delivery to an address that has received a
 * delivery is not held.
 */
class OrderOutcomeFlowIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired EpisodeRepository episodes;
    @Autowired RewardMaturityService rewards;
    @Autowired NodeRegistry nodes;
    @Autowired DecisionService decisions;
    @Autowired HoldService holds;
    @Autowired LoggingCustomerNotifier notifier;

    private List<Map<String, Object>> events(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :o and type = :ty order by position").param("t", tenant).param("o", orderId).param("ty", type)
            .query((rs, i) -> Json.readMap(rs.getString("p"))).list();
    }

    private FulfilmentGroup groupOf(Order o, String lineId) {
        return orders.get(o.id()).fulfilmentGroups().stream().filter(g -> !"rejected".equalsIgnoreCase(g.status()) && !"cancelled".equalsIgnoreCase(g.status()))
            .filter(g -> g.allocations().stream().anyMatch(a -> a.lineId().equals(lineId))).findFirst().orElseThrow();
    }

    private void shipLine(Order o, String lineId, int qty, String tracking) {
        FulfilmentGroup g = groupOf(o, lineId);
        inbound.wms(g.nodeId(), wmsEvent("ship-" + tracking, "shipped", o.id(), Map.of("fulfilmentGroupId", g.id(), "carrier", "UPS", "service", "ground",
            "tracking", tracking, "lines", List.of(Map.of("lineId", lineId, "qty", qty)))));
    }

    @Test
    void aParcelScanDeliversOnlyItsParcelAndATwoParcelOrderIsScoredOnce() {
        Order o = capture(webOrder("OUT-P1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        shipLine(o, "1", 2, "T-OUT-1");
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()));
        shipLine(o, "2", 1, "T-OUT-2");
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");

        Map<String, Object> scan = new HashMap<>(Map.of("eventId", "del-T-OUT-1", "type", "delivered", "orderId", o.id(), "tracking", "T-OUT-1", "damaged", true));
        inbound.carrier(scan);
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && "await_delivery".equals(w.currentStep()) && !events(o.id(), "order.delivered").isEmpty());
        assertThat(lineStates(orders.get(o.id()))).containsEntry("1", "delivered").containsEntry("2", "shipped");
        assertThat(orders.get(o.id()).status()).isNotEqualTo("delivered");
        assertThat(events(o.id(), "order.delivered")).singleElement().satisfies(e -> {
            assertThat((List<?>) e.get("shipments")).hasSize(1);
            assertThat(String.valueOf(e.get("lineIds"))).isEqualTo("[1]");
            assertThat(e).containsEntry("damaged", true).containsEntry("allDelivered", false).containsKey("fulfilmentGroupId");
        });

        inbound.carrier(Map.of("eventId", "del-T-OUT-2", "type", "delivered", "orderId", o.id(), "tracking", "T-OUT-2"));
        driveToTerminal(TYPE, o.id());
        assertThat(orders.get(o.id()).status()).isEqualTo("delivered");
        List<Map<String, Object>> delivered = events(o.id(), "order.delivered");
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(1)).containsEntry("allDelivered", true).containsEntry("damaged", false);
        assertThat(events(o.id(), "order.shipped")).hasSize(2).allSatisfy(e -> assertThat(e).containsKey("shipmentId"));

        // Reward: 3 of 3 units shipped in two parcels — fill 1, cost once, split for one extra parcel.
        drainOutbox();
        rewards.sweepOnce();
        Episode sourcing = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName())).findFirst().orElseThrow();
        Episode scored = episodes.find(sourcing.episodeId()).orElseThrow();
        assertThat(scored.rewardComponents().get("fill")).isCloseTo(1.0, within(1e-9));
        assertThat(scored.rewardComponents().get("split")).isCloseTo(0.5, within(1e-9));
        assertThat(scored.rewardComponents().get("cost")).isLessThan(0.5);
    }

    @Test
    void anOrderWithOnlyACustomerEmailGetsAPseudonymousReference() {
        CaptureRequest base = webOrder("OUT-EMAIL-1", "tok_visa_ok");
        CaptureRequest req = new CaptureRequest(base.channel(), base.channelOrderId(), base.orderType(), null,
            new CaptureRequest.CustomerInput(null, "Pat Lee", "Pat.Lee@Example.com", null, null, null, null, Map.of()),
            base.lines(), base.addresses(), base.charges(), base.tenders(), base.notes(), base.intentChoices(), base.shipMethod(), base.placedAt(), base.currency(), base.extensions(), base.provenance());
        Order o = capture(req);
        assertThat(o.customerRef()).doesNotContain("@").startsWith("em_");
        Order again = capture(new CaptureRequest(base.channel(), "OUT-EMAIL-2", base.orderType(), null,
            new CaptureRequest.CustomerInput(null, "Pat Lee", "pat.lee@example.com", null, null, null, null, Map.of()),
            base.lines(), base.addresses(), base.charges(), base.tenders(), base.notes(), base.intentChoices(), base.shipMethod(), base.placedAt(), base.currency(), base.extensions(), base.provenance()));
        assertThat(again.customerRef()).as("the same shopper maps to the same reference").isEqualTo(o.customerRef());
        Order marketplace = capture(new CaptureRequest("marketplace", "OUT-EMAIL-3", base.orderType(), "amazon:pat.lee@example.com", null,
            base.lines(), base.addresses(), base.charges(), base.tenders(), base.notes(), base.intentChoices(), base.shipMethod(), base.placedAt(), base.currency(), base.extensions(), base.provenance()));
        assertThat(marketplace.customerRef()).startsWith("amazon:em_").doesNotContain("@");

        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        drainOutbox();
        List<String> learningSubjects = jdbc.sql("""
            select subject_id from learned_fact where tenant_id = :t union all select subject_id from episode where tenant_id = :t
            union all select subject_id from customer_preference_evidence where tenant_id = :t
            """).param("t", tenant).query(String.class).list();
        assertThat(learningSubjects).noneMatch(s -> s != null && s.contains("@"));
        for (DecisionRecord r : decisions.forSubject("order", o.id())) assertThat(Json.write(r.input())).doesNotContain("Example.com").doesNotContain("example.com");
    }

    @Test
    void oneStoreRejectReSourcesOnceAndPenalisesOnlyTheRejectingNode() {
        Order o = capture(webOrder("OUT-REJ-1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        String first = nodeOf(o);
        long fromPosition = jdbc.sql("select coalesce(max(position), 0) from event").query(Long.class).single();
        inbound.wms(first, wmsEvent("rej-OUT-1", "store_rejected", o.id(), Map.of("reason", "no staff")));
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && "await_fulfilment".equals(w.currentStep())
            && w.vars().get("cycle") instanceof Number n && n.intValue() >= 1);
        drainOutbox();
        drainOutbox();
        assertThat(events(o.id(), "order.resourced")).as("exactly one re-sourcing").hasSize(1);
        List<String> penalised = jdbc.sql("select payload->>'nodeId' from event where tenant_id = :t and type = 'node.penalised' and position > :p")
            .param("t", tenant).param("p", fromPosition).query(String.class).list();
        assertThat(penalised).isNotEmpty().allMatch(first::equals);
    }

    private CaptureRequest cod(String ref, String customer) {
        return new CaptureRequest("web", ref, "standard_ship", customer, customer(customer),
            List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Ana Ruiz", "7 Lake View Ave", null, "Chicago", "IL", "60614", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("cod", "cod_cash", Money.usd("80.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
    }

    @Test
    void cashOnDeliveryToAnAddressThatReceivedADeliveryIsNotHeldAndAFirstTimeOneGetsOneMessage() {
        Order first = capture(cod("OUT-COD-1", "cust-cod-first"));
        driveToStep(TYPE, first.id(), WorkflowStatus.WAITING_SIGNAL, "release");
        assertThat(holds.active("order", first.id())).anyMatch(h -> PaymentRiskGate.ADDRESS_HOLD.equals(h.type()));
        assertThat(notifier.communicationsFor(first.id()).stream().filter(c -> "sms".equals(c.channel()))).as("one message, not two").hasSize(1);

        // Deliver an earlier order to the same address, then a new COD order there clears by rule.
        Order card = capture(new CaptureRequest("web", "OUT-COD-CARD", "standard_ship", "cust-cod-house", customer("cust-cod-house"),
            List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Ana Ruiz", "7 Lake View Ave", null, "Chicago", "IL", "60614", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd("80.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null));
        driveToStep(TYPE, card.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(card), wmsEvent("ship-cod-card", "shipped", card.id(), Map.of("carrier", "UPS", "tracking", "T-COD-CARD")));
        driveToStep(TYPE, card.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "del-cod-card", "type", "delivered", "orderId", card.id(), "tracking", "T-COD-CARD"));
        driveToTerminal(TYPE, card.id());

        Order repeat = capture(cod("OUT-COD-2", "cust-cod-second"));
        driveToStep(TYPE, repeat.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        DecisionRecord rto = decisions.forSubject("order", repeat.id()).stream().filter(r -> "rto_risk".equals(r.decision())).findFirst().orElseThrow();
        assertThat(rto.rulesFired()).containsExactly("prior_delivered_address");
        assertThat(holds.active("order", repeat.id())).noneMatch(h -> PaymentRiskGate.ADDRESS_HOLD.equals(h.type()));
        assertThat(notifier.communicationsFor(repeat.id()).stream().filter(c -> "sms".equals(c.channel()))).isEmpty();
    }
}
