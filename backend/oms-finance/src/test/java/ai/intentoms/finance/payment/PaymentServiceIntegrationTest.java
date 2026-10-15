package ai.intentoms.finance.payment;

import ai.intentoms.finance.FinanceTestBase;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-FIN-01 lifecycle against the deterministic mock gateway. */
class PaymentServiceIntegrationTest extends FinanceTestBase {
    @Autowired PaymentService payments;
    @Autowired EventStore events;
    @Autowired PaymentGateway gateway;

    @Test
    void splitTenderAuthorizeCapturePartialThenProportionalRefund() {
        String orderId = "ord_" + tenant;
        Payment card = payments.authorize(orderId, Money.usd("75.00"), "tok_visa_4242", "card", null);
        Payment gift = payments.authorize(orderId, Money.usd("25.00"), "gc_abc123", "gift_card", null);
        assertThat(gateway.name()).isEqualTo("mock");
        assertThat(card.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(card.pspRef()).isEqualTo("mock_auth_" + card.id());
        assertThat(card.authExpiresAt()).isNotNull();
        assertThat(gift.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(types("payment", card.id())).containsExactly("payment.authorized");

        // Partial capture: 80 of 100 → card fully captured (75), gift partially (5), in creation order.
        List<Payment> captured = payments.capture(orderId, Money.usd("80.00"), "shipped pkg 1");
        assertThat(captured).hasSize(2);
        Payment cardAfter = payments.get(card.id());
        Payment giftAfter = payments.get(gift.id());
        assertThat(cardAfter.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(cardAfter.amountCaptured()).isEqualTo(Money.usd("75.00"));
        assertThat(giftAfter.status()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
        assertThat(giftAfter.amountCaptured()).isEqualTo(Money.usd("5.00"));
        assertThat(types("payment", card.id())).containsExactly("payment.authorized", "payment.captured");
        Event capEvt = events.load("payment", gift.id()).get(1);
        assertThat(capEvt.payload()).containsEntry("reason", "shipped pkg 1");
        assertThat(capEvt.metadata()).containsEntry("orderId", orderId);

        // Capture the remaining 20 → gift fully captured; one more cent is refused before any gateway call.
        payments.capture(orderId, Money.usd("20.00"), "shipped pkg 2");
        assertThat(payments.get(gift.id()).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThatThrownBy(() -> payments.capture(orderId, Money.usd("0.01"), "too much")).isInstanceOf(ConflictException.class);

        // Refund 40 in original-tender proportion (75:25) → 30 to the card, 10 to the gift card.
        List<Refund> refunds = payments.refund(orderId, Money.usd("40.00"), "customer return", "rma_1", Actor.user("alice"));
        assertThat(refunds).hasSize(2);
        assertThat(refunds).allMatch(r -> r.status() == RefundStatus.SUCCEEDED);
        Map<String, Money> byPayment = Map.of(refunds.get(0).paymentId(), refunds.get(0).amount(), refunds.get(1).paymentId(), refunds.get(1).amount());
        assertThat(byPayment.get(card.id())).isEqualTo(Money.usd("30.00"));
        assertThat(byPayment.get(gift.id())).isEqualTo(Money.usd("10.00"));
        assertThat(refunds.get(0).returnId()).isEqualTo("rma_1");
        assertThat(refunds.get(0).requestedBy()).isEqualTo("user:alice");
        assertThat(refunds.get(0).pspRef()).isEqualTo("mock_re_" + refunds.get(0).id());
        assertThat(types("refund", refunds.get(0).id())).containsExactly("refund.requested", "refund.succeeded");
        assertThat(payments.refundsForOrder(orderId)).hasSize(2);

        // Remaining refundable is 60; asking for more is a conflict, asking for exactly 60 works.
        assertThatThrownBy(() -> payments.refund(orderId, Money.usd("60.01"), "x", null, Actor.user("alice"))).isInstanceOf(ConflictException.class);
        List<Refund> rest = payments.refund(orderId, Money.usd("60.00"), "full", null, Actor.user("alice"));
        assertThat(rest.stream().map(r -> r.amount().amount()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)).isEqualByComparingTo("60.00");
        assertThat(spendLedgerTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void declinedTenderIsPersistedAsFailedWithEvent() {
        String orderId = "ord_" + tenant;
        Payment p = payments.authorize(orderId, Money.usd("10.00"), "tok_card_decline", "card", null);
        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.failureCode()).isEqualTo("card_declined");
        assertThat(p.pspRef()).isNull();
        List<Event> evts = events.load("payment", p.id());
        assertThat(evts).hasSize(1);
        assertThat(evts.get(0).type()).isEqualTo("payment.failed");
        assertThat(evts.get(0).payload()).containsEntry("failureCode", "card_declined").containsEntry("stage", "authorize");
        assertThat(payments.forOrder(orderId)).hasSize(1);
        // Nothing is capturable on this order.
        assertThatThrownBy(() -> payments.capture(orderId, Money.usd("1.00"), "ship")).isInstanceOf(ConflictException.class);
    }

    @Test
    void reauthorizesOnlyPaymentsExpiringInsideTheWindow() {
        String orderId = "ord_" + tenant;
        Payment p = payments.authorize(orderId, Money.usd("50.00"), "tok_visa", "card", null);
        // Mock holds last 7 days: a 1-day window finds nothing, an 8-day window re-authorises.
        assertThat(payments.reauthorizeIfExpiring(orderId, Duration.ofDays(1))).isEmpty();
        List<Payment> re = payments.reauthorizeIfExpiring(orderId, Duration.ofDays(8));
        assertThat(re).hasSize(1);
        Payment after = re.get(0);
        assertThat(after.status()).isEqualTo(PaymentStatus.REAUTHORIZED);
        assertThat(after.pspRef()).isEqualTo("mock_reauth_" + p.id());
        assertThat(after.authExpiresAt()).isAfterOrEqualTo(p.authExpiresAt());
        assertThat(types("payment", p.id())).containsExactly("payment.authorized", "payment.reauthorized");
        // Still capturable afterwards.
        payments.capture(orderId, Money.usd("50.00"), "shipped");
        assertThat(payments.get(p.id()).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void expiredAuthorizationFailsCaptureWithAuthExpired() {
        String orderId = "ord_" + tenant;
        Payment p = payments.authorize(orderId, Money.usd("12.00"), "tok_card_expired", "card", null);
        List<Payment> result = payments.capture(orderId, Money.usd("12.00"), "ship");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.get(0).failureCode()).isEqualTo("auth_expired");
        assertThat(types("payment", p.id())).containsExactly("payment.authorized", "payment.failed");
    }

    @Test
    void voidReleasesUncapturedAuthorizationsOnly() {
        String orderId = "ord_" + tenant;
        Payment open = payments.authorize(orderId, Money.usd("20.00"), "tok_a", "card", null);
        Payment done = payments.authorize(orderId, Money.usd("5.00"), "gc_b", "gift_card", null);
        payments.capture(orderId, Money.usd("20.00"), "ship"); // consumes the card entirely (creation order)
        payments.voidAuthorization(orderId, "cancelled");
        assertThat(payments.get(open.id()).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payments.get(done.id()).status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(types("payment", done.id())).containsExactly("payment.authorized", "payment.voided");
    }

    @Test
    void agentTokenRequiresMandateAndKeepsItAsEvidence() {
        String orderId = "ord_" + tenant;
        assertThatThrownBy(() -> payments.authorize(orderId, Money.usd("9.00"), "agt_tok", "agent_token", null))
            .isInstanceOf(ai.intentoms.platform.error.ValidationException.class);
        Payment p = payments.authorize(orderId, Money.usd("9.00"), "agt_tok", "agent_token", Map.of("mandateId", "mnd_1", "agentId", "shopbot", "spendLimit", 50));
        assertThat(payments.get(p.id()).agentMandate()).containsEntry("mandateId", "mnd_1");
        assertThat(events.load("payment", p.id()).get(0).payload()).containsEntry("agentMandateId", "mnd_1");
    }

    private List<String> types(String aggregate, String id) { return events.load(aggregate, id).stream().map(Event::type).toList(); }

    private java.math.BigDecimal spendLedgerTotal() {
        return jdbc.sql("select coalesce(sum(amount),0) from spend_ledger where tenant_id = :t and category = 'refund'").param("t", tenant).query(java.math.BigDecimal.class).single();
    }
}
