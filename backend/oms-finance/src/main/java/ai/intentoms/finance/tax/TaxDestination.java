package ai.intentoms.finance.tax;

/**
 * Ship-to jurisdiction used for nexus / rate lookup.
 *
 * @param state   state or province code ("CA")
 * @param zip     postal code
 * @param country ISO 3166-1 alpha-2 ("US")
 * @param line1   street line, optional (improves Avalara jurisdiction resolution)
 * @param city    city, optional
 */
public record TaxDestination(String state, String zip, String country, String line1, String city) {
    public TaxDestination(String state, String zip, String country) { this(state, zip, country, null, null); }
}
