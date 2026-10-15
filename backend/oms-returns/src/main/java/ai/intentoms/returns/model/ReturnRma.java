package ai.intentoms.returns.model;

import java.time.Instant;
import java.util.List;

/**
 * The persisted RMA (table {@code return_rma} + {@code return_line}). {@code status} mirrors the dominant state of the
 * {@code return.standard} state machine entity {@code return/<rmaId>}. Decision record ids point at the records that
 * produced the risk score, refund timing, normalised reason and disposition (FR-XAI-01).
 */
public record ReturnRma(
    String rmaId,
    String orderId,
    String channel,
    String customerRef,
    String customerEmailToken,
    String status,
    String reasonCode,
    String customerReasonText,
    String condition,
    String refundTiming,
    Double riskScore,
    String riskLevel,
    List<ReasonCode> reasonCodes,
    String riskDecisionRecordId,
    String refundTimingDecisionRecordId,
    String reasonDecisionRecordId,
    String disposition,
    String dispositionDecisionRecordId,
    Eligibility eligibility,
    KeepIt keepIt,
    RefundInfo refund,
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
    String inspectionNote,
    Instant refundedAt,
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    List<ReturnLine> lines
) {
    public ReturnRma {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean refunded() { return refund != null && refund.succeeded(); }
    public boolean keepItOffered() { return keepIt != null && keepIt.offered(); }
    public boolean isTerminal() { return ReturnStatus.TERMINAL.contains(status); }

    /** Fluent copy helper: all mutable fields are set through the builder-like {@link Patch}. */
    public Patch patch() { return new Patch(this); }

    /** Mutable copy of an RMA used by the service to apply a set of field changes before persisting. */
    public static final class Patch {
        private String status, reasonCode, condition, refundTiming, riskLevel, riskDecisionRecordId, refundTimingDecisionRecordId, reasonDecisionRecordId,
            disposition, dispositionDecisionRecordId, reviewTaskId, reviewTaskType, receivedNodeId, inspectionNote;
        private Double riskScore;
        private List<ReasonCode> reasonCodes;
        private Eligibility eligibility;
        private KeepIt keepIt;
        private RefundInfo refund;
        private Instant scannedAt, receivedAt, inspectedAt, refundedAt;
        private List<ReturnLine> lines;
        private final ReturnRma base;

        private Patch(ReturnRma b) {
            this.base = b;
            status = b.status; reasonCode = b.reasonCode; condition = b.condition; refundTiming = b.refundTiming; riskLevel = b.riskLevel;
            riskDecisionRecordId = b.riskDecisionRecordId; refundTimingDecisionRecordId = b.refundTimingDecisionRecordId; reasonDecisionRecordId = b.reasonDecisionRecordId;
            disposition = b.disposition; dispositionDecisionRecordId = b.dispositionDecisionRecordId; reviewTaskId = b.reviewTaskId; reviewTaskType = b.reviewTaskType;
            receivedNodeId = b.receivedNodeId; inspectionNote = b.inspectionNote; riskScore = b.riskScore; reasonCodes = b.reasonCodes; eligibility = b.eligibility;
            keepIt = b.keepIt; refund = b.refund; scannedAt = b.scannedAt; receivedAt = b.receivedAt; inspectedAt = b.inspectedAt; refundedAt = b.refundedAt; lines = b.lines;
        }

        public Patch status(String v) { status = v; return this; }
        public Patch reasonCode(String v) { reasonCode = v; return this; }
        public Patch condition(String v) { condition = v; return this; }
        public Patch refundTiming(String v) { refundTiming = v; return this; }
        public Patch risk(Double score, String level, List<ReasonCode> codes, String recordId) { riskScore = score; riskLevel = level; reasonCodes = codes; riskDecisionRecordId = recordId; return this; }
        public Patch refundTimingDecisionRecordId(String v) { refundTimingDecisionRecordId = v; return this; }
        public Patch reasonDecisionRecordId(String v) { reasonDecisionRecordId = v; return this; }
        public Patch disposition(String v, String recordId) { disposition = v; dispositionDecisionRecordId = recordId; return this; }
        public Patch eligibility(Eligibility v) { eligibility = v; return this; }
        public Patch keepIt(KeepIt v) { keepIt = v; return this; }
        public Patch refund(RefundInfo v) { refund = v; return this; }
        public Patch reviewTask(String id, String type) { reviewTaskId = id; reviewTaskType = type; return this; }
        public Patch scannedAt(Instant v) { scannedAt = v; return this; }
        public Patch received(Instant at, String nodeId) { receivedAt = at; receivedNodeId = nodeId; return this; }
        public Patch inspected(Instant at, String note) { inspectedAt = at; inspectionNote = note; return this; }
        public Patch refundedAt(Instant v) { refundedAt = v; return this; }
        public Patch lines(List<ReturnLine> v) { lines = v; return this; }

        public ReturnRma build() {
            return new ReturnRma(base.rmaId, base.orderId, base.channel, base.customerRef, base.customerEmailToken, status, reasonCode, base.customerReasonText, condition,
                refundTiming, riskScore, riskLevel, reasonCodes, riskDecisionRecordId, refundTimingDecisionRecordId, reasonDecisionRecordId, disposition, dispositionDecisionRecordId,
                eligibility, keepIt, refund, base.receiptPresent, base.storeNodeId, base.agentId, base.mandateId, reviewTaskId, reviewTaskType, scannedAt, receivedAt, receivedNodeId,
                inspectedAt, inspectionNote, refundedAt, base.createdBy, base.createdAt, base.updatedAt, lines);
        }
    }
}
