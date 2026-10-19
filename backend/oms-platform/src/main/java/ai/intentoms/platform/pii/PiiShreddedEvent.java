package ai.intentoms.platform.pii;

import java.time.Instant;

/**
 * Published (Spring application event) when PII is crypto-shredded: {@code subjectRef} null means the tenant's whole
 * PII key was rotated ({@link Tokenizer#shred}); otherwise one customer was erased ({@link Tokenizer#shredSubject}) and
 * {@code pseudonym} is the token that replaces the customer reference wherever it is kept. Modules that hold raw PII or
 * data derived from it (orders, decision inputs, learned facts, preference evidence, segments) listen and erase or
 * pseudonymise what they hold (GV-06). Listeners must be idempotent: a repeated erasure request publishes again.
 */
public record PiiShreddedEvent(String tenantId, String subjectRef, String by, Instant at, String pseudonym) {
    public PiiShreddedEvent(String tenantId, String subjectRef, String by, Instant at) { this(tenantId, subjectRef, by, at, null); }

    public boolean tenantWide() { return subjectRef == null; }
}
