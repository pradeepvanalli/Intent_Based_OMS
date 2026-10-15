package ai.intentoms.finance.compliance;

/**
 * Customer facts rules may inspect (never PII).
 *
 * @param ageVerified whether the customer passed age verification at checkout
 * @param age         verified age in years, or null when unknown
 */
public record ComplianceCustomer(boolean ageVerified, Integer age) {
    public static ComplianceCustomer unverified() { return new ComplianceCustomer(false, null); }
}
