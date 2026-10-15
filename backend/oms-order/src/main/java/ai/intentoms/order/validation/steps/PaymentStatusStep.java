package ai.intentoms.order.validation.steps;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.ValidationContext;
import ai.intentoms.order.validation.ValidationIssue;
import ai.intentoms.order.validation.ValidationStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Step {@code payment_status} (FR-CAP-05): tenders must cover the expected total (merchandise + charges + estimated tax)
 * unless a deferred tender ({@code net_terms}) or a delegated agent payment ({@code agent_token}, FR-FIN-06) is present;
 * every card-like tender needs a PSP token (never a PAN). Zero-total orders need no tender.
 */
@Component
public class PaymentStatusStep implements ValidationStep {
    public static final String NAME = "payment_status";
    /** Tender types that settle later or through a mandate, so the amount need not be covered up front. */
    static final Set<String> DEFERRED = Set.of("net_terms", "agent_token");
    static final Set<String> TOKENLESS = Set.of("net_terms", "cash", "invoice");

    @Override public String name() { return NAME; }

    @Override
    public List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx) {
        List<ValidationIssue> out = new ArrayList<>();
        Money expected = ctx.expectedTotal() == null ? req.merchandiseTotal().add(req.chargeTotal()) : ctx.expectedTotal();
        boolean deferred = false;
        for (CaptureRequest.TenderInput t : req.tenders()) {
            if (t.type() == null) continue;
            if (DEFERRED.contains(t.type())) deferred = true;
            if (!TOKENLESS.contains(t.type()) && (t.token() == null || t.token().isBlank())) {
                out.add(ValidationIssue.block("tender_token_required", "tenders[" + t.type() + "].token", "tender " + t.type() + " needs a payment token"));
            }
        }
        if (expected.isPositive() && req.tenders().isEmpty()) {
            out.add(ValidationIssue.block("tender_required", "tenders", "no tender given for an order totalling " + expected));
            return out;
        }
        if (!deferred && expected.isPositive()) {
            Money tendered = req.tenderTotal();
            if (tendered.compareTo(expected) < 0) {
                out.add(ValidationIssue.block("tender_insufficient", "tenders", "tenders " + tendered + " do not cover the order total " + expected));
            }
        }
        return out;
    }
}
