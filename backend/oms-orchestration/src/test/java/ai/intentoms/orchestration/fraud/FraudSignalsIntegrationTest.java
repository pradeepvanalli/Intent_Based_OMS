package ai.intentoms.orchestration.fraud;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.ChargebackService;
import ai.intentoms.finance.payment.Payment;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.contact.CustomerContactService;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.TaskQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FD-01 / FD-02 / FD-06 / OC-03 through the real workflow, decision fabric (heuristic provider), holds, tasks and
 * ledger: clean orders clear by rule, a card-testing pattern is held until a reviewer clears it, a cash-on-delivery
 * order from an unknown address gets the prepaid offer and an address hold, a refused COD parcel and a chargeback
 * label the right decisions, the shipped event carries the outcome facts, and contacts are recorded once.
 */
class FraudSignalsIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired DecisionService decisions;
    @Autowired HoldService holds;
    @Autowired ChargebackService chargebacks;
    @Autowired CustomerContactService contacts;

    private CaptureRequest order(String channelOrderId, String customer, String tenderType, String token) {
        return new CaptureRequest("web", channelOrderId, "standard_ship", customer, customer(customer),
            List.of(line("1", "HOOD-GRY-M", 2, "68.00"), line("2", "SNK-RUN-10", 1, "145.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Sam Okafor", "12 Harbour Rd", null, "Chicago", "IL", "60607", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput(tenderType, token, Money.usd("320.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
    }

    private DecisionRecord record(String orderId, String decision) {
        return decisions.forSubject("order", orderId).stream().filter(r -> decision.equals(r.decision())).findFirst()
            .orElseThrow(() -> new AssertionError("no " + decision + " record for " + orderId));
    }

    private List<Hold> activeHolds(String orderId, String type) {
        return holds.active("order", orderId).stream().filter(h -> type.equals(h.type())).toList();
    }

    private List<Map<String, Object>> events(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :o and type = :ty order by position").param("t", tenant).param("o", orderId).param("ty", type)
            .query((rs, i) -> Json.readMap(rs.getString("p"))).list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> label(String orderId, String decision) {
        Object l = record(orderId, decision).labels().get("fraud_outcome");
        return l instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @Test
    void cleanOrderClearsByRuleAndShipsWithOutcomeFacts() {
        Order o = capture(webOrder("FR-CLEAN-1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");

        DecisionRecord pr = record(o.id(), "payment_risk");
        assertThat(pr.layer()).isEqualTo(Layer.L0_RULE);
        assertThat(pr.rulesFired()).containsExactly("no_risk_signals");
        assertThat(pr.value()).isEqualTo(false);
        assertThat(Json.path(pr.input(), "payment.velocity_1h")).isEqualTo(0);
        assertThat(Json.path(pr.input(), "address.token")).isNotNull();
        assertThat(activeHolds(o.id(), PaymentRiskGate.PAYMENT_HOLD)).isEmpty();
        assertThat(decisions.forSubject("order", o.id())).noneMatch(r -> "rto_risk".equals(r.decision()));

        inbound.wms(nodeOf(o), wmsEvent("wms-FR1", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "1ZFR1")));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        Map<String, Object> shipped = events(o.id(), "order.shipped").get(0);
        assertThat(shipped).containsEntry("packageCount", 1).containsEntry("splitAvoided", true).containsEntry("storeRejected", false)
            .containsEntry("unitsShipped", 3).containsEntry("unitsOrdered", 3).containsEntry("customerRef", "cust-1001").containsKey("fulfilmentGroupId");
        assertThat(((Number) shipped.get("orderValue")).doubleValue()).isPositive();
        assertThat((List<?>) shipped.get("nodeIds")).hasSize(1);

        inbound.carrier(Map.of("eventId", "car-FR1", "type", "delivered", "orderId", o.id(), "tracking", "1ZFR1"));
        driveToTerminal(TYPE, o.id());
        drainOutbox();
        assertThat(label(o.id(), "payment_risk")).containsEntry("confirmed", false).containsEntry("kind", "delivered_no_chargeback").containsEntry("provisional", true);

        // FD-06: a fraud chargeback later replaces the provisional label; a retry changes nothing.
        Payment pay = payments.forOrder(o.id()).get(0);
        Map<String, Object> cb = chargebacks.record(new ChargebackService.Notice("stripe", "dp_fr1", pay.pspRef(), null, "fraudulent", "OPEN"));
        assertThat(cb).containsEntry("matched", true).containsEntry("created", true).containsEntry("fraud", true);
        assertThat(chargebacks.record(new ChargebackService.Notice("stripe", "dp_fr1", pay.pspRef(), null, "fraudulent", "OPEN"))).containsEntry("created", false);
        drainOutbox();
        assertThat(label(o.id(), "payment_risk")).containsEntry("confirmed", true).containsEntry("kind", "chargeback_fraud");
    }

    @Test
    void cardTestingPatternIsHeldUntilAReviewerClearsIt() {
        capture(order("FR-VEL-1", "cust-vel", "card", "tok_vel_a"));
        capture(order("FR-VEL-2", "cust-vel", "card", "tok_vel_b"));
        Order o = capture(order("FR-VEL-3", "cust-vel", "card", "tok_vel_c"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "release");

        DecisionRecord pr = record(o.id(), "payment_risk");
        assertThat(Json.path(pr.input(), "payment.velocity_1h")).isEqualTo(2);
        assertThat(pr.rulesFired()).isEmpty();
        assertThat(pr.requiresHuman()).isTrue();
        assertThat(activeHolds(o.id(), PaymentRiskGate.PAYMENT_HOLD)).hasSize(1);
        assertThat(lineStates(orders.get(o.id())).values()).doesNotContain("released");

        List<HumanTask> open = tasks.list(new TaskQuery(null, "OPEN", null, "order", o.id(), null, 0, 10)).items().stream()
            .filter(t -> "decision_escalation".equals(t.type()) && "payment_risk".equals(t.payload().get("decision"))).toList();
        assertThat(open).hasSize(1);
        tasks.complete(open.get(0).id(), "not_risky", "known customer, new cards after a lost wallet", Map.of("value", false));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        assertThat(activeHolds(o.id(), PaymentRiskGate.PAYMENT_HOLD)).isEmpty();
        assertThat(lineStates(orders.get(o.id())).values()).containsOnly("released");
    }

    @Test
    void cashOnDeliveryFromAnUnknownAddressGetsOfferAndAddressHoldThenRefusalLabelsRtoRisk() {
        Order o = capture(order("FR-COD-1", "cust-cod-new", "cod", "cod_cash"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "release");

        assertThat(record(o.id(), "payment_risk").rulesFired()).containsExactly("no_risk_signals");
        DecisionRecord rto = record(o.id(), "rto_risk");
        assertThat(Json.path(rto.input(), "order.payment_method")).isEqualTo("cod");
        assertThat(rto.rulesFired()).isEmpty();
        List<Hold> addressHold = activeHolds(o.id(), PaymentRiskGate.ADDRESS_HOLD);
        assertThat(addressHold).singleElement().satisfies(h -> assertThat(h.expiresAt()).isNotNull());
        assertThat(events(o.id(), "order.cod_prepaid_offered")).singleElement().satisfies(e -> assertThat(e).containsEntry("discountPct", 5.0));
        assertThat(events(o.id(), "order.address_confirmation_requested")).hasSize(1);
        assertThat(payments.forOrder(o.id())).singleElement().satisfies(p -> assertThat(p.psp()).isEqualTo("cod"));

        // The customer confirms (ops releases the hold): the order moves on, ships, and the parcel is refused at the door.
        holds.release(addressHold.get(0).id(), "customer confirmed by phone", SYSTEM);
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("wms-COD1", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "1ZCOD1")));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "car-COD1", "type", "returned_to_sender", "orderId", o.id(), "tracking", "1ZCOD1"));
        drive(TYPE, o.id(), w -> !events(o.id(), "order.returned_to_sender").isEmpty());
        drainOutbox();
        assertThat(events(o.id(), "order.returned_to_sender").get(0)).containsEntry("cod", true).containsKey("addressToken").containsEntry("customerRef", "cust-cod-new");
        assertThat(label(o.id(), "rto_risk")).containsEntry("confirmed", true).containsEntry("kind", "refused_delivery");

        // FD-02 features remember the refusal: the next COD order from this customer sees it.
        Order next = capture(order("FR-COD-2", "cust-cod-new", "cod", "cod_cash"));
        driveToStep(TYPE, next.id(), WorkflowStatus.WAITING_SIGNAL, "release");
        assertThat(Json.path(record(next.id(), "rto_risk").input(), "customer.history.refused_12m")).isEqualTo(1);
        assertThat(Json.path(record(next.id(), "rto_risk").input(), "address.prior_refused_count")).isEqualTo(1);
    }

    @Test
    void customerContactsAreRecordedOncePerExternalId() {
        Order o = capture(webOrder("FR-CS-1", "tok_visa_ok"));
        assertThat(contacts.record(o.id(), new CustomerContactService.Contact("wismo", "chat", "zendesk", "zd-1"))).containsEntry("recorded", true).containsEntry("type", "cs.wismo");
        assertThat(contacts.record(o.id(), new CustomerContactService.Contact("wismo", "chat", "zendesk", "zd-1"))).containsEntry("recorded", false).containsEntry("duplicate", true);
        assertThat(contacts.record(o.id(), new CustomerContactService.Contact("complaint", "phone", "ivr", null))).containsEntry("type", "cs.complaint");
        assertThat(events(o.id(), "cs.wismo")).singleElement().satisfies(e -> assertThat(e).containsEntry("customerRef", "cust-1001").containsEntry("channel", "chat"));
        assertThat(events(o.id(), "cs.complaint")).hasSize(1);
    }
}
