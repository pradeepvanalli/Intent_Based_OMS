package ai.intentoms.finance.compliance;

import java.util.List;

/**
 * Everything the L0 compliance rules need (FR-FIN-03, FR-CAP-05).
 *
 * @param lines       order lines with item attributes
 * @param destination ship-to facts
 * @param customer    customer facts (age verification)
 * @param shipMethod  carrier service code, e.g. {@code ground}, {@code air_2day}, {@code air_overnight}, {@code international_air}
 */
public record ComplianceInput(List<ComplianceLine> lines, ComplianceDestination destination, ComplianceCustomer customer, String shipMethod) {}
