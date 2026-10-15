package ai.intentoms.order.capture;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.model.OrderLine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enriches captured lines from the catalogue (FR-CAP-05, FR-FIN-04): line attributes are the catalogue's snake_case facts
 * (hazmat, age_restricted, weight_kg, cogs, hs_code, lithium, export_controlled, oversized, final_sale, digital, category,
 * restricted_states) overlaid with whatever the request supplied, so a connector can override a flag per order while
 * the catalogue remains the default source of truth.
 */
public final class LineEnricher {
    private LineEnricher() {}

    public static Map<String, Object> attributes(CaptureRequest.LineInput l, CatalogItem item) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (item != null) attrs.putAll(item.lineAttributes());
        if (l.attributes() != null) l.attributes().forEach((k, v) -> { if (v != null) attrs.put(k, v); });
        if (l.taxCode() != null) attrs.put("tax_code", l.taxCode());
        return attrs;
    }

    public static OrderLine toLine(CaptureRequest.LineInput l, CatalogItem item, String currency) {
        Money price = l.unitPrice() == null ? Money.zero(currency) : l.unitPrice();
        Money discount = l.discount() == null ? Money.zero(currency) : l.discount();
        String description = l.description() != null ? l.description() : item == null ? l.sku() : item.name();
        return new OrderLine(l.lineId(), l.sku(), description, l.qty(), price, discount, Money.zero(currency), null, null, attributes(l, item));
    }
}
