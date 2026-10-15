package ai.intentoms.agent.mandate;

import ai.intentoms.finance.model.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * A buyer's delegation to an agent (section 10 "Mandate", FR-CAP-07, FR-FIN-06): buyer reference, scope, spend limit,
 * instruction text, signature and expiry. Stored as dispute evidence. JSON matches the UI {@code Mandate}: {@code scope}
 * is the label list, {@code scopeDetail} the structured scope, {@code usedOrderIds} the orders placed under it.
 *
 * @param signatureValid null when unsigned, true/false after verification against the agent's key or HMAC secret
 * @param paymentToken   tokenised delegated payment credential; exposed only as presence
 * @param injectionRisk  probability from the injection screen over the instruction text (GR-08)
 */
public record Mandate(String id, String agentId, String buyerRef, @JsonProperty("scopeDetail") MandateScope scope, Money spendLimit, String instructionText, String signature,
                      Boolean signatureValid, @JsonIgnore String paymentToken, Double injectionRisk, String injectionRecordId, Instant issuedAt, Instant expiresAt,
                      String status, List<String> usedOrderIds, Instant createdAt, Instant updatedAt) {
    public static final String ACTIVE = "active";
    public static final String CONSUMED = "consumed";
    public static final String EXPIRED = "expired";
    public static final String REVOKED = "revoked";

    public Mandate {
        usedOrderIds = usedOrderIds == null ? List.of() : List.copyOf(usedOrderIds);
        scope = scope == null ? MandateScope.empty() : scope;
    }

    @JsonProperty("scope")
    public List<String> scopeLabels() { return scope.labels(); }

    @JsonProperty("paymentTokenPresent")
    public boolean paymentTokenPresent() { return paymentToken != null && !paymentToken.isBlank(); }

    public boolean expired(Instant now) { return expiresAt != null && !expiresAt.isAfter(now); }

    /** Status as seen at {@code now}: an active mandate past its expiry reads as expired without a sweeper. */
    public String effectiveStatus(Instant now) { return ACTIVE.equals(status) && expired(now) ? EXPIRED : status; }

    public Mandate withStatus(String s, List<String> used) {
        return new Mandate(id, agentId, buyerRef, scope, spendLimit, instructionText, signature, signatureValid, paymentToken, injectionRisk, injectionRecordId, issuedAt, expiresAt, s, used, createdAt, updatedAt);
    }
}
