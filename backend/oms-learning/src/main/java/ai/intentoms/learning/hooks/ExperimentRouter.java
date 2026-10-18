package ai.intentoms.learning.hooks;

import ai.intentoms.decision.runtime.DecisionLearningHooks;

import java.util.Optional;

/** EX-03 / EX-04: sticky canary and holdout assignment for a running experiment on this decision. */
public interface ExperimentRouter {
    Optional<DecisionLearningHooks.Route> route(String decision, String routingKey, int resolvedVersion);
}
