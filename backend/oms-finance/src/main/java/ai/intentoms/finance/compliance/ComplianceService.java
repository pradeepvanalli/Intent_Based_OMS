package ai.intentoms.finance.compliance;

import java.util.List;

/**
 * Deterministic L0 compliance check (FR-FIN-03, FR-CAP-05, GR-01 hard constraint): restricted items by state and
 * ZIP, age-restricted goods, hazmat routing, lithium batteries, export controls. Rules come from the
 * {@code compliance_rule} table (seeded from {@code config/tenants/<t>/rules/compliance.yaml} via
 * {@link ComplianceRuleLoader}) merged with {@code tenant_config compliance.rules}. Never calls a model.
 */
public interface ComplianceService {
    /** Returns every triggered rule; an empty list means the order is compliant. */
    List<ComplianceViolation> check(ComplianceInput in);

    /** Active rules in evaluation order (for the UI and static checks). */
    List<ComplianceRule> rules();
}
