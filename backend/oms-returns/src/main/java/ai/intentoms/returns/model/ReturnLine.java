package ai.intentoms.returns.model;

import ai.intentoms.finance.model.Money;

import java.util.Map;

/**
 * A returned line as stored on the RMA (FR-RET-01, FR-RET-04). {@code tax} is the tax share of the returned quantity,
 * taken proportionally from the order line's tax.
 */
public record ReturnLine(String lineId, String sku, String description, int qty, Money unitPrice, Money tax, String reasonCode,
                         String customerReasonText, String condition, String disposition, Map<String, Object> attributes) {
    public ReturnLine {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Merchandise value of the returned quantity. */
    public Money extended() { return unitPrice.multiply(qty); }

    public ReturnLine withCondition(String c) { return new ReturnLine(lineId, sku, description, qty, unitPrice, tax, reasonCode, customerReasonText, c, disposition, attributes); }
    public ReturnLine withDisposition(String d) { return new ReturnLine(lineId, sku, description, qty, unitPrice, tax, reasonCode, customerReasonText, condition, d, attributes); }

    public boolean flag(String key) {
        Object v = attributes.get(key);
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString());
    }
}
