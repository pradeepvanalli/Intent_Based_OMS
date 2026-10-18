package ai.intentoms.learning.governance;

/**
 * Implemented by every learner bean. {@link LearnerRegistry} collects them so {@code GET /learning/learners} lists
 * every learner from the first boot, and every run goes through {@link LearnerGovernance#gate}.
 */
public interface GovernedLearner {
    LearnerDescriptor descriptor();

    /** True for batch learners that can be run on demand ({@code POST /learning/learners/{id}/run}); event-driven
     * learners update on every event instead. */
    default boolean runnable() { return false; }

    /** One run for the current tenant, still through the governance gate. */
    default void runNow() { throw new UnsupportedOperationException(descriptor().key() + " learns from events and has no batch run"); }
}
