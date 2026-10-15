package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.util.Map;

/**
 * One ordered SKU. Quantity-level status lives in the state machine (entity "order_line", id "<orderId>:<lineId>").
 * Attributes carry catalogue facts that drive compliance and sourcing: hazmat, age_restricted, weight_kg, cogs,
 * hs_code, lithium, oversized, final_sale, digital, category, restricted_states.
 */
public record OrderLine(
    String lineId,
    String sku,
    String description,
    int qty,
    Money unitPrice,
    Money discount,
    Money tax,
    String fulfilmentGroupId,
    String status,
    Map<String, Object> attributes
) {
    public boolean hazmat() { return flag("hazmat"); }
    public boolean ageRestricted() { return flag("age_restricted"); }
    public boolean finalSale() { return flag("final_sale"); }
    public boolean digital() { return flag("digital"); }
    public double weightKg() { Object w = attributes == null ? null : attributes.get("weight_kg"); return w instanceof Number n ? n.doubleValue() : 0.5; }
    public Money cogs() {
        Object c = attributes == null ? null : attributes.get("cogs");
        return c instanceof Number n ? new Money(java.math.BigDecimal.valueOf(n.doubleValue()), unitPrice.currency()) : Money.zero(unitPrice.currency());
    }
    public Money extendedPrice() { return unitPrice.multiply(qty).subtract(discount == null ? Money.zero(unitPrice.currency()) : discount); }
    private boolean flag(String k) { Object v = attributes == null ? null : attributes.get(k); return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString()); }

    public OrderLine withQty(int newQty) { return new OrderLine(lineId, sku, description, newQty, unitPrice, discount, tax, fulfilmentGroupId, status, attributes); }
    public OrderLine withFulfilmentGroup(String groupId) { return new OrderLine(lineId, sku, description, qty, unitPrice, discount, tax, groupId, status, attributes); }
    public OrderLine withStatus(String s) { return new OrderLine(lineId, sku, description, qty, unitPrice, discount, tax, fulfilmentGroupId, s, attributes); }
    public OrderLine withTax(Money t) { return new OrderLine(lineId, sku, description, qty, unitPrice, discount, t, fulfilmentGroupId, status, attributes); }
}
