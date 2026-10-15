package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.returns.model.RefundTiming;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.model.ReturnStatus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Customer-facing text for a return (GR-15): states only facts from the RMA record and typed decision outputs. Amounts,
 * dates and the refund timing fill typed slots from fixed phrase maps; risk scores, reason codes, bands and task ids
 * never appear. No commitments outside policy: timing phrases describe the policy that applies, not promises.
 */
@org.springframework.stereotype.Component
public class ReturnMessages {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneOffset.UTC);

    /** Fixed phrases per refund timing; the model's score never reaches the customer. */
    static final Map<String, String> TIMING_PHRASES = Map.of(
        RefundTiming.INSTANT, "Your refund has been approved and is on its way back to your original payment method.",
        RefundTiming.ON_CARRIER_SCAN, "Your refund will be issued as soon as the carrier scans your return package.",
        RefundTiming.ON_INSPECTION, "Your refund will be issued once we have received and inspected your return.",
        RefundTiming.STORE_CREDIT_ONLY, "Once we have received and inspected your return, the value will be issued as store credit.",
        RefundTiming.ESCALATE, "Your return is being reviewed by our team; we will update you shortly."
    );

    static final Map<String, String> STATUS_PHRASES = Map.of(
        ReturnStatus.REQUESTED, "We have received your return request.",
        ReturnStatus.APPROVED, "Your return has been approved.",
        ReturnStatus.AWAITING_RECEIPT, "Your return label has been issued; please send the item back within 14 days.",
        ReturnStatus.RECEIVED, "We have received your return.",
        ReturnStatus.INSPECTED, "We have inspected your return.",
        ReturnStatus.REFUNDED, "Your refund has been issued.",
        ReturnStatus.KEEP_IT_REFUNDED, "You may keep the item; your refund has been issued.",
        ReturnStatus.REJECTED, "We are unable to accept this return.",
        ReturnStatus.CANCELLED, "Your return request has been cancelled."
    );

    /** Customer-safe denial phrases keyed by policy reason code (GR-16: denials carry reason codes, phrased from policy only). */
    static final Map<String, String> DENIAL_PHRASES = Map.of(
        ReturnPolicy.OUT_OF_WINDOW, "The return window for this order has closed.",
        ReturnPolicy.FINAL_SALE, "This item was sold as final sale and cannot be returned.",
        ReturnPolicy.DIGITAL_GOODS, "Digital items cannot be returned."
    );

    /** The message with its typed slots, for the UI and for channel templates. */
    public Map<String, Object> customerMessage(ReturnRma rma) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rma", rma.rmaId());
        out.put("status", rma.status());
        out.put("text", customerText(rma));
        Map<String, Object> slots = new LinkedHashMap<>();
        if (rma.refund() != null && rma.refund().amount() != null) slots.put("refundAmount", new Money(rma.refund().amount(), rma.refund().currency()));
        if (rma.refund() != null && rma.refund().restockingFee() != null && rma.refund().restockingFee().signum() > 0) slots.put("restockingFee", new Money(rma.refund().restockingFee(), rma.refund().currency()));
        if (rma.refundTiming() != null) slots.put("refundTiming", rma.refundTiming());
        if (rma.refundedAt() != null) slots.put("refundedOn", DATE.format(rma.refundedAt()));
        if (rma.createdAt() != null) slots.put("requestedOn", DATE.format(rma.createdAt()));
        if (rma.eligibility() != null && !rma.eligibility().eligible()) slots.put("policyReasons", rma.eligibility().reasons());
        out.put("slots", slots);
        return out;
    }

    /** Plain text for e-mail / SMS / agent channels. */
    public String customerText(ReturnRma rma) {
        StringBuilder sb = new StringBuilder();
        sb.append("Return ").append(rma.rmaId()).append(" for order ").append(rma.orderId()).append(": ");
        if (ReturnStatus.REJECTED.equals(rma.status()) || (rma.eligibility() != null && !rma.eligibility().eligible() && ReturnStatus.REQUESTED.equals(rma.status()))) {
            sb.append(ReturnStatus.REJECTED.equals(rma.status()) ? STATUS_PHRASES.get(ReturnStatus.REJECTED) : "Your return request is being reviewed against our return policy.");
            if (rma.eligibility() != null) for (String r : rma.eligibility().reasons()) {
                String phrase = DENIAL_PHRASES.get(r);
                if (phrase != null) sb.append(' ').append(phrase);
            }
            return sb.toString().trim();
        }
        sb.append(STATUS_PHRASES.getOrDefault(rma.status(), "Your return is in progress."));
        if (rma.refunded() && rma.refund().amount() != null) {
            sb.append(" Refund amount: ").append(format(new Money(rma.refund().amount(), rma.refund().currency())));
            if (rma.refund().restockingFee() != null && rma.refund().restockingFee().signum() > 0) sb.append(" (after a restocking fee of ").append(format(new Money(rma.refund().restockingFee(), rma.refund().currency()))).append(')');
            if (RefundTiming.STORE_CREDIT_ONLY.equals(rma.refundTiming()) || (rma.refund().method() != null && rma.refund().method().equals("store_credit"))) sb.append(", issued as store credit");
            sb.append('.');
            if (rma.refundedAt() != null) sb.append(" Issued on ").append(DATE.format(rma.refundedAt())).append('.');
        } else if (rma.refund() != null && rma.refund().awaitingApproval()) {
            sb.append(" Your refund is being processed.");
        } else if (rma.refundTiming() != null && !rma.isTerminal()) {
            String phrase = TIMING_PHRASES.get(rma.refundTiming());
            if (phrase != null) sb.append(' ').append(phrase);
        }
        if (rma.keepItOffered() && !rma.refunded()) sb.append(" You do not need to send the item back.");
        return sb.toString().trim();
    }

    static String format(Money m) { return m.amount().toPlainString() + " " + m.currency(); }

    static String date(Instant i) { return DATE.format(i); }
}
