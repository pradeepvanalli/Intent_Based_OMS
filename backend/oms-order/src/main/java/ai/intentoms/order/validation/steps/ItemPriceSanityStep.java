package ai.intentoms.order.validation.steps;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.validation.ValidationContext;
import ai.intentoms.order.validation.ValidationIssue;
import ai.intentoms.order.validation.ValidationStep;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Step {@code item_price_sanity} (FR-CAP-05): every sku must exist in the catalogue (block {@code unknown_sku}, or warn
 * when {@code capture.allow_unknown_sku} is true) and its unit price must be plausible against the catalogue price:
 * outside ±{@code capture.price_tolerance} (default 0.25) warns {@code price_out_of_tolerance}; more than twice the
 * catalogue price, or a zero price for a priced item, blocks {@code price_implausible}.
 */
@Component
public class ItemPriceSanityStep implements ValidationStep {
    public static final String NAME = "item_price_sanity";

    @Override public String name() { return NAME; }

    @Override
    public List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx) {
        List<ValidationIssue> out = new ArrayList<>();
        for (CaptureRequest.LineInput l : req.lines()) {
            if (l.sku() == null || l.unitPrice() == null) continue;
            String ref = "lines[" + l.lineId() + "]";
            CatalogItem item = ctx.catalogItem(l.sku());
            if (item == null) {
                out.add(ctx.allowUnknownSku()
                    ? ValidationIssue.warn("unknown_sku", ref + ".sku", "sku " + l.sku() + " is not in the catalogue; accepted because capture.allow_unknown_sku is on")
                    : ValidationIssue.block("unknown_sku", ref + ".sku", "sku " + l.sku() + " is not in the catalogue"));
                continue;
            }
            Money catalogPrice = item.price(l.unitPrice().currency());
            if (catalogPrice == null || catalogPrice.isZero()) continue;
            BigDecimal ratio = l.unitPrice().amount().divide(catalogPrice.amount(), 6, RoundingMode.HALF_EVEN);
            if (ratio.compareTo(BigDecimal.valueOf(2)) > 0) {
                out.add(ValidationIssue.block("price_implausible", ref + ".unitPrice", "unit price " + l.unitPrice() + " is more than twice the catalogue price " + catalogPrice));
            } else if (l.unitPrice().isZero()) {
                out.add(ValidationIssue.block("price_implausible", ref + ".unitPrice", "unit price is 0 for priced item " + l.sku() + " (catalogue " + catalogPrice + ")"));
            } else if (ratio.subtract(BigDecimal.ONE).abs().doubleValue() > ctx.priceTolerance()) {
                out.add(ValidationIssue.warn("price_out_of_tolerance", ref + ".unitPrice", "unit price " + l.unitPrice() + " deviates from catalogue price " + catalogPrice + " by more than " + Math.round(ctx.priceTolerance() * 100) + "%"));
            }
        }
        return out;
    }
}
