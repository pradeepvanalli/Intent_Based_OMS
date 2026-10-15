package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One SKU of the tenant catalogue (seeded from {@code catalog.yaml}). {@code attributes} keeps the raw camelCase
 * facts (price, cogs, weightKg, hazmat, hazmatClass, ageRestricted, category, lithium, exportControlled, oversized,
 * finalSale, digital, restrictedStates, hsCode); {@link #lineAttributes()} renders the snake_case view copied onto
 * order lines so compliance rules, sourcing and P&L read one vocabulary (FR-CAP-02, FR-CAP-05).
 */
public record CatalogItem(String sku, String name, Map<String, Object> attributes) {
    public CatalogItem {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public Money price(String currency) {
        Object p = attributes.get("price");
        return p instanceof Number n ? new Money(new BigDecimal(n.toString()), currency) : null;
    }

    public BigDecimal cogs() { Object c = attributes.get("cogs"); return c instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO; }

    public boolean flag(String key) { Object v = attributes.get(key); return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString()); }

    /** Snake_case attribute map placed on {@link OrderLine#attributes()}. */
    public Map<String, Object> lineAttributes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hazmat", flag("hazmat"));
        if (attributes.get("hazmatClass") != null) m.put("hazmat_class", attributes.get("hazmatClass"));
        m.put("age_restricted", flag("ageRestricted"));
        if (attributes.get("minAge") instanceof Number n) m.put("min_age", n.intValue());
        if (attributes.get("weightKg") instanceof Number n) m.put("weight_kg", n.doubleValue());
        m.put("cogs", cogs());
        if (attributes.get("hsCode") != null) m.put("hs_code", attributes.get("hsCode"));
        m.put("lithium", flag("lithium"));
        m.put("lithium_battery", flag("lithium"));
        m.put("export_controlled", flag("exportControlled"));
        m.put("oversized", flag("oversized"));
        m.put("final_sale", flag("finalSale"));
        m.put("digital", flag("digital"));
        if (attributes.get("category") != null) m.put("category", attributes.get("category"));
        Object rs = attributes.get("restrictedStates");
        if (rs instanceof List<?> l && !l.isEmpty()) m.put("restricted_states", l.stream().map(String::valueOf).toList());
        if (attributes.get("price") instanceof Number n) m.put("catalog_price", new BigDecimal(n.toString()));
        return m;
    }
}
