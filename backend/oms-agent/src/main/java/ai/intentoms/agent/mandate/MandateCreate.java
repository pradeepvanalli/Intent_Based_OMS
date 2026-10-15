package ai.intentoms.agent.mandate;

import ai.intentoms.finance.model.Money;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Duration;
import java.time.Instant;

/**
 * Mandate issuance request (FR-CAP-07). {@code scope} accepts the object form or the UI's label list. Either
 * {@code expiresAt} or {@code ttl} sets the expiry (default 24 h). {@code signature} is the buyer-side signature over the
 * canonical JSON (see {@link MandateSigner}); {@code paymentToken} is the delegated payment credential (FR-FIN-06).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MandateCreate(@JsonAlias({"mandateId"}) String id, @JsonAlias({"agent_id"}) String agentId, @JsonAlias({"buyer_ref"}) String buyerRef, Object scope,
                            @JsonAlias({"spend_limit"}) Money spendLimit, @JsonAlias({"instruction_text", "instructions"}) String instructionText, String signature,
                            @JsonAlias({"payment_token"}) String paymentToken, @JsonAlias({"expires_at"}) Instant expiresAt, Duration ttl) {}
