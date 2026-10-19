package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.returns.model.RefundInfo;
import ai.intentoms.returns.model.RefundLeg;
import ai.intentoms.returns.model.ReturnLine;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.model.ReturnStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OC-03 / FD-06: return events carry what the reward needs, and grading a wrong item back confirms return fraud. */
class ReturnOutcomeFactsTest {
    private static ReturnRma rma(String condition, RefundInfo refund) {
        ReturnLine line = new ReturnLine("L1", "TEE", "tee", 2, Money.usd("28.00"), Money.usd("2.00"), "not_as_described", null, condition, null, Map.of());
        return new ReturnRma("rma_1", "ord_1", "web", "cust_1", null, ReturnStatus.INSPECTED, "not_as_described", null, condition, null, null, null,
            List.of(), null, null, null, "restock", null, null, null, refund, true, null, null, null, null, null, null, null, null, null, null, null,
            "user:cs", Instant.now(), Instant.now(), List.of(line));
    }

    @Test
    void wrongItemIsConfirmedFraudAndThePendingRefundIsTheLinesValue() {
        Map<String, Object> p = ReturnService.outcomeFacts(rma("wrong_item", null), Map.of("source", "decision"));
        assertThat(p).containsEntry("orderId", "ord_1").containsEntry("reasonCode", "not_as_described").containsEntry("condition", "wrong_item")
            .containsEntry("fraudConfirmed", true).containsEntry("refundAmount", 56.0).containsEntry("source", "decision").containsEntry("rmaId", "rma_1");
    }

    @Test
    void aLikeNewReturnIsNotFraudAndUsesTheIssuedRefund() {
        RefundInfo refund = new RefundInfo(new BigDecimal("50.00"), "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, RefundInfo.ORIGINAL_TENDER,
            List.of(new RefundLeg("ref_1", "pay_1", new BigDecimal("50.00"), "succeeded")), RefundInfo.SUCCEEDED, null, null, null, Instant.now());
        Map<String, Object> p = ReturnService.outcomeFacts(rma("like_new", refund), Map.of());
        assertThat(p).containsEntry("fraudConfirmed", false).containsEntry("refundAmount", 50.0);
    }
}
