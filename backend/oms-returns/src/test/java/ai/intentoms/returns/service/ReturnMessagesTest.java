package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.returns.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GR-15: customer text uses typed slots only and never leaks risk data. */
class ReturnMessagesTest {
    private final ReturnMessages messages = new ReturnMessages();

    private static ReturnRma rma(String status, String timing, RefundInfo refund, Eligibility elig, KeepIt keepIt, Instant refundedAt) {
        ReturnLine line = new ReturnLine("L1", "TEE", "tee", 1, Money.usd("28.00"), Money.usd("2.00"), "changed_mind", null, null, null, Map.of());
        return new ReturnRma("rma_1", "ord_1", "web", "cust_1", null, status, "changed_mind", "it did not fit", null, timing, 3.7, "severe",
            List.of(new ReasonCode("serial_returner", 0.92), new ReasonCode("many_not_received_claims", 0.88)), "rec_risk", "rec_risk", null, null, null, elig, keepIt, refund,
            true, null, null, null, null, null, null, null, null, null, null, refundedAt, "user:cs", Instant.parse("2026-09-01T10:00:00Z"), Instant.now(), List.of(line));
    }

    @Test
    void refundedMessageStatesAmountAndDateOnly() {
        RefundInfo refund = new RefundInfo(new BigDecimal("30.00"), "USD", new BigDecimal("0.00"), new BigDecimal("2.00"), BigDecimal.ZERO, RefundInfo.ORIGINAL_TENDER,
            List.of(new RefundLeg("ref_1", "pay_1", new BigDecimal("30.00"), "succeeded")), RefundInfo.SUCCEEDED, null, null, null, Instant.parse("2026-09-03T12:00:00Z"));
        String text = messages.customerText(rma(ReturnStatus.REFUNDED, RefundTiming.INSTANT, refund, new Eligibility(true, List.of(), 30, 2, false), null, Instant.parse("2026-09-03T12:00:00Z")));
        assertThat(text).contains("rma_1", "ord_1", "Your refund has been issued", "30.00 USD", "3 September 2026");
        assertNoRiskData(text);
    }

    @Test
    void pendingMessageUsesFixedTimingPhrasesAndNeverTheScore() {
        for (String timing : List.of(RefundTiming.INSTANT, RefundTiming.ON_CARRIER_SCAN, RefundTiming.ON_INSPECTION, RefundTiming.STORE_CREDIT_ONLY, RefundTiming.ESCALATE)) {
            String text = messages.customerText(rma(ReturnStatus.AWAITING_RECEIPT, timing, null, new Eligibility(true, List.of(), 30, 2, false), null, null));
            assertThat(text).contains(ReturnMessages.TIMING_PHRASES.get(timing));
            assertNoRiskData(text);
        }
    }

    @Test
    void denialMessageExplainsPolicyReasonsOnly() {
        String text = messages.customerText(rma(ReturnStatus.REJECTED, null, null, new Eligibility(false, List.of("out_of_window", "final_sale"), 30, 45, true), null, null));
        assertThat(text).contains("We are unable to accept this return", "return window for this order has closed", "final sale");
        assertNoRiskData(text);
    }

    @Test
    void keepItTellsTheCustomerNotToShipAndSlotsAreTyped() {
        RefundInfo refund = new RefundInfo(new BigDecimal("18.00"), "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, RefundInfo.ORIGINAL_TENDER, List.of(), RefundInfo.PENDING, null, null, null, null);
        ReturnRma r = rma(ReturnStatus.APPROVED, RefundTiming.INSTANT, refund, new Eligibility(true, List.of(), 30, 1, false), new KeepIt(true, "return_cost_exceeds_recovery", new BigDecimal("10.50"), new BigDecimal("7.20")), null);
        String text = messages.customerText(r);
        assertThat(text).contains("You do not need to send the item back");
        Map<String, Object> msg = messages.customerMessage(r);
        assertThat(msg).containsKeys("rma", "status", "text", "slots");
        @SuppressWarnings("unchecked") Map<String, Object> slots = (Map<String, Object>) msg.get("slots");
        assertThat(slots.get("refundAmount")).isEqualTo(Money.usd("18.00"));
        assertThat(slots).doesNotContainKeys("riskScore", "reasonCodes", "riskLevel");
        assertNoRiskData(text);
    }

    private static void assertNoRiskData(String text) {
        assertThat(text).doesNotContain("3.7", "severe", "serial_returner", "many_not_received_claims", "rec_risk", "risk", "score", "band", "task");
    }
}
