package ai.intentoms.order.validation;

import java.util.List;

/**
 * Outcome of {@link AddressVerifier}. {@code status}: {@code verified} | {@code unverified} (not checked, e.g. non-US) |
 * {@code invalid} (format problems) | {@code undeliverable}. {@code addressType}: residential | commercial | po_box when
 * the verifier could tell, else the caller's value.
 */
public record AddressVerification(String status, String addressType, Double lat, Double lon, List<String> messages) {
    public AddressVerification { messages = messages == null ? List.of() : List.copyOf(messages); }
    public boolean verified() { return "verified".equals(status); }
    public boolean deliverable() { return !"undeliverable".equals(status) && !"invalid".equals(status); }
}
