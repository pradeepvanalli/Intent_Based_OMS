package ai.intentoms.returns.model;

import ai.intentoms.finance.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API view of an RMA matching the UI contract ({@code ui/src/api/types.ts ReturnRecord}, MODULE_NOTES item 11):
 * risk score, reason codes with probabilities, the decision record ids behind risk / timing / disposition, eligibility,
 * refund, keep-it, plus lines, order summary fields and the ledger timeline of aggregate {@code return/<rma>}.
 */
public record ReturnRecord(
    String rma,
    String orderId,
    String channelOrderId,
    String orderChannel,
    String channel,
    String customerRef,
    String status,
    String reason,
    String customerReasonText,
    List<Line> lines,
    Instant createdAt,
    String createdBy,
    Double riskScore,
    String riskLevel,
    List<ReasonCode> reasonCodes,
    String riskDecisionRecordId,
    String reasonDecisionRecordId,
    String refundTiming,
    String refundTimingDecisionRecordId,
    String disposition,
    String dispositionDecisionRecordId,
    EligibilityView eligibility,
    RefundView refund,
    KeepItView keepIt,
    String condition,
    String inspectionNote,
    boolean receiptPresent,
    String storeNodeId,
    String agentId,
    String mandateId,
    String reviewTaskId,
    String reviewTaskType,
    Instant scannedAt,
    Instant receivedAt,
    String receivedNodeId,
    Instant inspectedAt,
    Instant refundedAt,
    Money refundAmount,
    Money merchandiseTotal,
    List<TimelineEntry> timeline
) {
    public record Line(String lineId, String sku, String description, int qty, Money unitPrice, Money tax, String reason, String customerReasonText, String condition, String disposition) {}
    public record EligibilityView(boolean eligible, List<String> reasons, Integer windowDays, Integer daysSinceDelivery, boolean finalSale) {}
    public record RefundView(Money amount, Money restockingFee, Money taxRefunded, Money shippingRefunded, String method, String status, List<RefundLeg> legs, String approvalTaskId, String holdReason, String approvedBy, Instant issuedAt) {}
    /** {@code recommended} is the UI contract name for {@code offered}; both are emitted. */
    public record KeepItView(boolean recommended, boolean offered, String reason, Money returnCost, Money recovery) {}
    public record TimelineEntry(Instant at, String kind, String name, String status, String actor, Map<String, Object> details) {}
}
