package ai.intentoms.learning.governance;

import ai.intentoms.learning.model.AutonomyLevel;

import java.util.List;

/**
 * What a learner is, for governance and for people: its key, a plain-English name and one-line summary for the
 * Learning Console, which loop it runs in, the decisions whose outcomes it learns from (OC-07 pauses only the
 * learners reading a decision whose coverage breaks), the input features it may read (GV-07 allowlist), the live
 * parameters it can feed through {@link ParameterResolver}, and its autonomy on first registration (the doc's
 * default is L1, recommend only).
 */
public record LearnerDescriptor(String key, String displayName, String summary, Loop loop, List<String> outcomeDecisions,
                                List<String> features, List<String> liveParameters, AutonomyLevel defaultAutonomy, String requirement) {
    public enum Loop { FAST, SLOW }

    public static LearnerDescriptor of(String key, String displayName, String summary, Loop loop, String requirement) {
        return new LearnerDescriptor(key, displayName, summary, loop, List.of(), List.of(), List.of(), AutonomyLevel.L1_RECOMMEND, requirement);
    }

    public LearnerDescriptor readsOutcomesOf(String... decisions) {
        return new LearnerDescriptor(key, displayName, summary, loop, List.of(decisions), features, liveParameters, defaultAutonomy, requirement);
    }

    public LearnerDescriptor features(String... names) {
        return new LearnerDescriptor(key, displayName, summary, loop, outcomeDecisions, List.of(names), liveParameters, defaultAutonomy, requirement);
    }

    public LearnerDescriptor feeds(String... parameters) {
        return new LearnerDescriptor(key, displayName, summary, loop, outcomeDecisions, features, List.of(parameters), defaultAutonomy, requirement);
    }
}
