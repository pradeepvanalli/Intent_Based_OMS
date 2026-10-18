package ai.intentoms.integrations.payments;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentDispute;
import ai.intentoms.finance.payment.PaymentRepository;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.integrations.IntegrationsTestBase;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.order.model.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FD-06: Stripe dispute webhooks become chargebacks on the right payment, once, with the agent's dispute recorded. */
class StripeWebhookTest extends IntegrationsTestBase {
    @Autowired MockMvc mvc;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepo;

    private static String dispute(String type, String disputeId, String paymentIntent, String reason, String status) {
        return """
            {"id":"evt_%s","type":"%s","data":{"object":{"id":"%s","object":"dispute","amount":32000,"currency":"usd",
             "charge":"ch_123","payment_intent":"%s","reason":"%s","status":"%s"}}}
            """.formatted(disputeId, type, disputeId, paymentIntent, reason, status);
    }

    private Payment authorisedPayment(String channelOrderId, Map<String, Object> mandate) {
        Order o = capture(webOrder(channelOrderId));
        return payments.authorize(o.id(), Money.usd("320.00"), "tok_ok_1", mandate.isEmpty() ? "card" : "agent_token", mandate);
    }

    private List<Map<String, Object>> events(String type, String aggregateId) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and type = :ty and aggregate_id = :a").param("t", tenant).param("ty", type).param("a", aggregateId)
            .query((rs, i) -> ai.intentoms.platform.json.Json.readMap(rs.getString("p"))).list();
    }

    @Test
    void disputeOpensOnceThenCloses() throws Exception {
        Payment p = authorisedPayment("STRIPE-1", Map.of());
        String created = dispute("charge.dispute.created", "dp_1", p.pspRef(), "fraudulent", "needs_response");

        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant).content(created))
            .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(true)).andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.fraud").value(true)).andExpect(jsonPath("$.orderId").value(p.orderId()));
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant).content(created))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        bind();
        assertThat(paymentRepo.disputesForOrder(p.orderId())).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo(PaymentDispute.OPEN);
            assertThat(d.amount().amount()).isEqualByComparingTo("320.00");
            assertThat(d.fraud()).isTrue();
        });
        assertThat(events("payment.chargeback", p.id())).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("orderId", p.orderId()).containsEntry("fraud", true).containsEntry("reasonCode", "fraudulent");
        });

        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant)
                .content(dispute("charge.dispute.closed", "dp_1", p.pspRef(), "fraudulent", "won")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WON"));
        bind();
        assertThat(paymentRepo.disputesForOrder(p.orderId())).singleElement().satisfies(d -> assertThat(d.status()).isEqualTo(PaymentDispute.WON));
        assertThat(events("payment.chargeback_won", p.id())).hasSize(1);
    }

    @Test
    void agentOrderDisputeIsRecordedOnTheAgentAndNonFraudReasonIsNotFraud() throws Exception {
        Payment p = authorisedPayment("STRIPE-2", Map.of("agentId", "shopbot-ucp", "mandateId", "mdt_1"));
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant)
                .content(dispute("charge.dispute.created", "dp_2", p.pspRef(), "product_not_received", "needs_response")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fraud").value(false));
        bind();
        assertThat(events("agent.dispute_recorded", "shopbot-ucp")).singleElement().satisfies(e -> assertThat(e).containsEntry("orderId", p.orderId()));
    }

    @Test
    void unknownPaymentAndOtherEventsAreAcknowledged() throws Exception {
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant)
                .content(dispute("charge.dispute.created", "dp_3", "pi_unknown", "fraudulent", "needs_response")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(false));
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant)
                .content("{\"id\":\"evt_x\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{}}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ignored").value(true));
    }

    @Test
    void signedEndpointRequiresAValidStripeSignature() throws Exception {
        config.set(IntegrationsConfigKeys.STRIPE_WEBHOOK_SECRET, ai.intentoms.platform.json.Json.write("whsec_test"));
        Payment p = authorisedPayment("STRIPE-4", Map.of());
        String body = dispute("charge.dispute.created", "dp_4", p.pspRef(), "fraudulent", "needs_response");
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant)
                .header("Stripe-Signature", StripeSignatures.header("whsec_wrong", body, Instant.now().getEpochSecond())).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant)
                .header("Stripe-Signature", StripeSignatures.header("whsec_test", body, Instant.now().getEpochSecond() - 3600)).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/payments/stripe").contentType("application/json").header("X-Tenant-Id", tenant)
                .header("Stripe-Signature", StripeSignatures.header("whsec_test", body, Instant.now().getEpochSecond())).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));
    }
}
