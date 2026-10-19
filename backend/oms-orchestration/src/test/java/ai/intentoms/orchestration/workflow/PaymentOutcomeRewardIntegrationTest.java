package ai.intentoms.orchestration.workflow;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.ChargebackService;
import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentRepository;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Payment outcomes in the reward, through the real producers (the chargeback service and the carrier webhook):
 * <ul>
 *   <li>audit A-N2: a chargeback the merchant won blames nobody (OC-05), and its loss is cancelled;</li>
 *   <li>a refused cash-on-delivery parcel comes back, so its fraud loss is the return-to-origin cost (the outbound
 *       fulfilment cost again), not the order's whole value.</li>
 * </ul>
 */
class PaymentOutcomeRewardIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired EpisodeRepository episodes;
    @Autowired RewardMaturityService rewards;
    @Autowired ChargebackService chargebacks;
    @Autowired PaymentRepository payments;
    @Autowired HoldService holds;

    private Episode orderEpisode(Order o) {
        drainOutbox();
        rewards.sweepOnce();
        Episode ep = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName()) && e.fulfillmentGroupId() == null && e.lineId() == null)
            .findFirst().orElseThrow();
        return episodes.find(ep.episodeId()).orElseThrow();
    }

    @Test
    void aWonChargebackBlamesNobodyAndCostsNothing() {
        Order o = capture(webOrder("PAYOUT-CB", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("ship-PAYOUT-CB", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "T-PAYOUT-CB")));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "del-PAYOUT-CB", "type", "delivered", "orderId", o.id(), "tracking", "T-PAYOUT-CB"));
        driveToTerminal(TYPE, o.id());

        Payment pay = payments.forOrder(o.id()).get(0);
        chargebacks.record(new ChargebackService.Notice("stripe", "dp_payout_cb", pay.pspRef(), null, "fraudulent", "OPEN"));
        Episode disputed = orderEpisode(o);
        assertThat(disputed.rewardComponents()).as("an open dispute is the customer's").containsEntry("attr.customer", 1.0);
        assertThat(disputed.rewardComponents().get("fraud")).isPositive();

        chargebacks.record(new ChargebackService.Notice("stripe", "dp_payout_cb", pay.pspRef(), null, "fraudulent", "WON"));
        Episode won = orderEpisode(o);
        assertThat(won.rewardComponents()).as("the merchant won: nobody to blame").doesNotContainKey("attr.customer");
        assertThat(won.rewardComponents().get("fraud")).isZero();
        assertThat(won.rewardComponents()).as("a dispute is not a fulfilment failure").containsEntry("perfect", 1.0);
    }

    @Test
    void aRefusedCashOnDeliveryParcelCostsTheReturnLegNotTheOrder() {
        CaptureRequest req = new CaptureRequest("web", "PAYOUT-COD", "standard_ship", "cust-payout-cod", customer("cust-payout-cod"),
            List.of(line("1", "HOOD-GRY-M", 2, "68.00"), line("2", "SNK-RUN-10", 1, "145.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Sam Okafor", "12 Harbour Rd", null, "Chicago", "IL", "60607", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("cod", "cod_cash", Money.usd("320.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
        Order o = capture(req);
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "release");
        holds.active("order", o.id()).forEach(h -> holds.release(h.id(), "customer confirmed by phone", SYSTEM));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("ship-PAYOUT-COD", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "T-PAYOUT-COD")));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "rts-PAYOUT-COD", "type", "returned_to_sender", "orderId", o.id(), "tracking", "T-PAYOUT-COD"));
        drive(TYPE, o.id(), w -> jdbc.sql("select count(*) from event where tenant_id = :t and aggregate_id = :o and type = 'order.returned_to_sender'")
            .param("t", tenant).param("o", o.id()).query(Long.class).single() > 0);

        Episode refused = orderEpisode(o);
        Map<String, Double> c = refused.rewardComponents();
        double cost = c.getOrDefault("cost_raw", c.get("cost"));
        assertThat(cost).as("the outbound fulfilment cost share").isPositive().isLessThan(1.0);
        double fraud = c.getOrDefault("fraud_raw", c.get("fraud"));
        assertThat(fraud).as("the return leg costs what the outbound leg did, not the order's value").isCloseTo(cost, within(1e-6)).isLessThan(1.0);
        assertThat(c).as("the customer turned the parcel away").containsEntry("attr.customer", 1.0).containsEntry("perfect", 0.0);
    }
}
