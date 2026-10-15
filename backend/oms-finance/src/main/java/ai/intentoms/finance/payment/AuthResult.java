package ai.intentoms.finance.payment;

import java.time.Instant;
import java.util.Map;

/**
 * Outcome of an authorise / re-authorise call.
 *
 * @param approved      true when the hold was placed
 * @param pspRef        PSP authorisation reference (PaymentIntent id, mock ref); null when declined
 * @param authExpiresAt when the hold lapses and must be re-authorised (FR-FIN-01)
 * @param failureCode   gateway decline code when not approved (e.g. {@code card_declined})
 * @param failureMessage human-readable decline reason
 * @param raw           selected raw gateway fields kept for dispute evidence
 */
public record AuthResult(boolean approved, String pspRef, Instant authExpiresAt, String failureCode, String failureMessage, Map<String, Object> raw) {
    public static AuthResult approved(String pspRef, Instant expiresAt, Map<String, Object> raw) { return new AuthResult(true, pspRef, expiresAt, null, null, raw == null ? Map.of() : raw); }
    public static AuthResult declined(String code, String message, Map<String, Object> raw) { return new AuthResult(false, null, null, code, message, raw == null ? Map.of() : raw); }
}
