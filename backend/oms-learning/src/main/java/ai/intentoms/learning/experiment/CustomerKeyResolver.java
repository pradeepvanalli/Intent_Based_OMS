package ai.intentoms.learning.experiment;

import java.util.Optional;

/**
 * EX-03 / EX-04: the customer a decision subject belongs to, so experiments split by customer rather than by order: the
 * same customer is in the same canary or holdout arm across every decision and every order. Implemented in the app glue,
 * which can read orders and returns; without an implementation, a subject routes on its own id.
 */
public interface CustomerKeyResolver {
    /** The customer reference for this subject (e.g. an order's customer), when known. */
    Optional<String> customerOf(String subjectType, String subjectId);
}
