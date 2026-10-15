package ai.intentoms.order.validation;

import ai.intentoms.finance.compliance.ComplianceViolation;
import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.model.OrderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facts shared by the validation steps of one capture (FR-CAP-05) plus the side effects they request: holds to place
 * once the order exists (compliance, agent verification) and address verification outcomes to record on the order.
 * Built by the order service; steps only read the facts and append requests, so they stay unit-testable without Spring.
 */
public final class ValidationContext {
    /** A hold a step asks the order service to place after capture (scope order). */
    public record HoldRequest(String type, Set<String> blocks, String reasonCode, String note) {}

    private final OrderType orderType;
    private final Map<String, CatalogItem> catalog;
    private final Money expectedTotal;
    private final boolean allowUnknownSku;
    private final double priceTolerance;
    private final Map<String, Object> extensionSchema;
    private final List<HoldRequest> holds = new ArrayList<>();
    private final Map<String, AddressVerification> addressResults = new LinkedHashMap<>();
    private final List<ComplianceViolation> violations = new ArrayList<>();

    public ValidationContext(OrderType orderType, Map<String, CatalogItem> catalog, Money expectedTotal, boolean allowUnknownSku, double priceTolerance, Map<String, Object> extensionSchema) {
        this.orderType = orderType;
        this.catalog = catalog == null ? Map.of() : Map.copyOf(catalog);
        this.expectedTotal = expectedTotal;
        this.allowUnknownSku = allowUnknownSku;
        this.priceTolerance = priceTolerance;
        this.extensionSchema = extensionSchema;
    }

    public OrderType orderType() { return orderType; }
    /** Catalogue items for the request's skus; unknown skus are absent. */
    public Map<String, CatalogItem> catalog() { return catalog; }
    public CatalogItem catalogItem(String sku) { return sku == null ? null : catalog.get(sku); }
    /** Merchandise + charges + estimated tax, the amount tenders must cover. */
    public Money expectedTotal() { return expectedTotal; }
    public boolean allowUnknownSku() { return allowUnknownSku; }
    public double priceTolerance() { return priceTolerance; }
    /** Tenant extension JSON schema for entity {@code order}, or null when none is configured. */
    public Map<String, Object> extensionSchema() { return extensionSchema; }

    public void requestHold(String type, Set<String> blocks, String reasonCode, String note) { holds.add(new HoldRequest(type, blocks, reasonCode, note)); }
    public List<HoldRequest> holds() { return List.copyOf(holds); }

    public void addressVerified(String addressType, AddressVerification result) { addressResults.put(addressType, result); }
    public Map<String, AddressVerification> addressResults() { return Map.copyOf(addressResults); }

    public void addViolations(List<ComplianceViolation> v) { violations.addAll(v); }
    public List<ComplianceViolation> violations() { return List.copyOf(violations); }
}
