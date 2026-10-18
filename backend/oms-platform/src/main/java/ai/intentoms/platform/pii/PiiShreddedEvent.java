package ai.intentoms.platform.pii;

import java.time.Instant;

/**
 * Published (Spring application event) when PII is crypto-shredded: {@code subjectRef} null means the tenant's whole
 * PII key was rotated ({@link Tokenizer#shred}); otherwise one subject (a customer reference) was deleted. Modules
 * that derive data from tokenized identities (learned facts, segments) listen and delete what they hold (GV-06).
 */
public record PiiShreddedEvent(String tenantId, String subjectRef, String by, Instant at) {
    public boolean tenantWide() { return subjectRef == null; }
}
