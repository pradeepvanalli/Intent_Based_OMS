package ai.intentoms.sourcing.model;

/** Quantity of one order line allocated to a package (FR-SRC-01). */
public record Allocation(String lineId, String sku, int qty) {}
