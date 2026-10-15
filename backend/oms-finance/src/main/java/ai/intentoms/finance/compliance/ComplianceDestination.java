package ai.intentoms.finance.compliance;

/**
 * Ship-to address facts rules may inspect.
 *
 * @param state       state / province code ("UT")
 * @param zip         postal code
 * @param country     ISO 3166-1 alpha-2 ("US")
 * @param addressType {@code residential} | {@code commercial} | {@code po_box} | {@code military} | null when unknown
 */
public record ComplianceDestination(String state, String zip, String country, String addressType) {
    public ComplianceDestination(String state, String zip, String country) { this(state, zip, country, null); }
}
