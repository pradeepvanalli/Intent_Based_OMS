package ai.intentoms.finance.compliance;

import java.util.Map;

/**
 * One order line as seen by the compliance rules (FR-FIN-03).
 *
 * @param lineId     order line id reported on violations
 * @param sku        item identifier
 * @param qty        quantity
 * @param attributes item attributes; well-known keys {@code hazmat}, {@code age_restricted}, {@code category},
 *                   {@code export_controlled}, {@code lithium_battery}, {@code min_age} are defaulted when absent so
 *                   rules can reference them directly as {@code line.hazmat} etc.
 */
public record ComplianceLine(String lineId, String sku, int qty, Map<String, Object> attributes) {}
