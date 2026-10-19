package ai.intentoms.learning.store;

import ai.intentoms.platform.pii.Tokenizer;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * NF-07 "tokenised ids only, per the existing PII vault": the one way learning names a customer. Every CUSTOMER
 * learning subject (learned facts, preference evidence, order attribution, segment membership, episodes about a
 * customer, {@code fact.learned} events) is keyed by the vault token of the customer reference,
 * {@code Tokenizer.token("cu", ref)}: the same token the erasure path writes as the customer's pseudonym, so an erased
 * customer's learned data is found (and deleted) under the name it was learned by. Every reader converts the same way
 * (the customer feature contributor, the CI learners, the preferences view, sourcing's segment lookup and the
 * experiment router's customer key). A reference that already is such a token (an erased customer's pseudonymised
 * order) is kept as it is.
 */
@Component
public class CustomerSubjects {
    /** A vault customer token: {@code cu_} and 20 hex digits. */
    public static final Pattern TOKEN = Pattern.compile("^cu_[0-9a-f]{20}$");

    private final Tokenizer tokenizer;

    public CustomerSubjects(Tokenizer tokenizer) { this.tokenizer = tokenizer; }

    /** The learning subject id of a customer reference (null for null or blank). Needs the tenant context. */
    public String key(String customerRef) {
        if (customerRef == null || customerRef.isBlank()) return null;
        String ref = customerRef.strip();
        return isToken(ref) ? ref : tokenizer.token("cu", ref);
    }

    public static boolean isToken(String id) { return id != null && TOKEN.matcher(id).matches(); }
}
