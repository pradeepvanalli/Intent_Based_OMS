package ai.intentoms.order.validation.steps;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.ExtensionSchemaService;
import ai.intentoms.order.validation.ValidationContext;
import ai.intentoms.order.validation.ValidationIssue;
import ai.intentoms.order.validation.ValidationStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step {@code schema} (FR-CAP-05): structural sanity of the request: channel and channelOrderId present, at least one
 * line, unique line ids, sku present, qty > 0, unit price present and non-negative in the order currency, discounts and
 * tender amounts non-negative, address types present, and {@code extensions} valid against the tenant's extension JSON
 * Schema (FR-CAP-02 typed extensions). Every finding blocks.
 */
@Component
public class SchemaValidationStep implements ValidationStep {
    public static final String NAME = "schema";
    private static final Set<String> ADDRESS_TYPES = Set.of("ship_to", "bill_to", "pickup");

    @Override public String name() { return NAME; }

    @Override
    public List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx) {
        List<ValidationIssue> out = new ArrayList<>();
        if (blank(req.channel())) out.add(ValidationIssue.block("channel_required", "channel", "channel is required"));
        if (blank(req.channelOrderId())) out.add(ValidationIssue.block("channel_order_id_required", "channelOrderId", "channelOrderId is required"));
        String ccy = req.effectiveCurrency();
        if (!ccy.matches("^[A-Z]{3}$")) out.add(ValidationIssue.block("currency_invalid", "currency", "currency must be an ISO 4217 code"));
        if (req.lines().isEmpty()) out.add(ValidationIssue.block("lines_required", "lines", "at least one line is required"));
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (CaptureRequest.LineInput l : req.lines()) {
            String ref = "lines[" + (l.lineId() == null ? String.valueOf(i) : l.lineId()) + "]";
            if (blank(l.lineId())) out.add(ValidationIssue.block("line_id_required", ref + ".lineId", "every line needs a lineId"));
            else if (!seen.add(l.lineId())) out.add(ValidationIssue.block("line_id_duplicate", ref + ".lineId", "duplicate lineId " + l.lineId()));
            if (blank(l.sku())) out.add(ValidationIssue.block("sku_required", ref + ".sku", "sku is required"));
            if (l.qty() <= 0) out.add(ValidationIssue.block("qty_invalid", ref + ".qty", "qty must be greater than 0"));
            if (l.unitPrice() == null) out.add(ValidationIssue.block("unit_price_required", ref + ".unitPrice", "unitPrice is required"));
            else {
                if (l.unitPrice().isNegative()) out.add(ValidationIssue.block("unit_price_negative", ref + ".unitPrice", "unitPrice must be >= 0"));
                if (!ccy.equals(l.unitPrice().currency())) out.add(ValidationIssue.block("currency_mismatch", ref + ".unitPrice.currency", "line currency " + l.unitPrice().currency() + " differs from order currency " + ccy));
            }
            if (l.discount() != null && l.discount().isNegative()) out.add(ValidationIssue.block("discount_negative", ref + ".discount", "discount must be >= 0"));
            if (l.discount() != null && l.unitPrice() != null && !l.unitPrice().isNegative() && l.qty() > 0 && l.discount().currency().equals(ccy)
                && l.discount().compareTo(l.unitPrice().multiply(l.qty())) > 0) out.add(ValidationIssue.block("discount_exceeds_price", ref + ".discount", "discount exceeds the line value"));
            i++;
        }
        for (CaptureRequest.AddressInput a : req.addresses()) {
            if (blank(a.type())) out.add(ValidationIssue.block("address_type_required", "addresses[].type", "address type is required (ship_to, bill_to, pickup)"));
            else if (!ADDRESS_TYPES.contains(a.type())) out.add(ValidationIssue.block("address_type_unknown", "addresses[" + a.type() + "].type", "unknown address type " + a.type()));
        }
        for (CaptureRequest.TenderInput t : req.tenders()) {
            if (blank(t.type())) out.add(ValidationIssue.block("tender_type_required", "tenders[].type", "tender type is required"));
            if (t.amount() == null) out.add(ValidationIssue.block("tender_amount_required", "tenders[].amount", "tender amount is required"));
            else if (t.amount().isNegative()) out.add(ValidationIssue.block("tender_amount_negative", "tenders[].amount", "tender amount must be >= 0"));
            else if (!ccy.equals(t.amount().currency())) out.add(ValidationIssue.block("currency_mismatch", "tenders[].amount.currency", "tender currency differs from order currency " + ccy));
        }
        for (CaptureRequest.ChargeInput c : req.charges()) {
            if (c.amount() == null) out.add(ValidationIssue.block("charge_amount_required", "charges[].amount", "charge amount is required"));
            else if (!ccy.equals(c.amount().currency())) out.add(ValidationIssue.block("currency_mismatch", "charges[].amount.currency", "charge currency differs from order currency " + ccy));
        }
        if (ctx.extensionSchema() != null && !ctx.extensionSchema().isEmpty()) {
            for (String m : ExtensionSchemaService.validate(ctx.extensionSchema(), req.extensions())) {
                out.add(ValidationIssue.block("extension_schema", "extensions", m));
            }
        }
        return out;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    static Money zeroIfNull(Money m, String ccy) { return m == null ? Money.zero(ccy) : m; }
}
