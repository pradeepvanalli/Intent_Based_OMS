package ai.intentoms.finance.compliance;

/**
 * One triggered compliance rule.
 *
 * @param ruleId     id of the rule that fired
 * @param action     {@code block} | {@code hold} | {@code require_signature}
 * @param reasonCode machine-readable reason, e.g. {@code HAZMAT_AIR}
 * @param lineId     offending line, or null for order-level rules
 * @param message    human-readable explanation for CS and the customer
 * @param severity   {@code error} | {@code warning}
 */
public record ComplianceViolation(String ruleId, String action, String reasonCode, String lineId, String message, String severity) {
    public boolean blocks() { return "block".equals(action); }
}
