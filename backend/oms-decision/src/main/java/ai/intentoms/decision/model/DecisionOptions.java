package ai.intentoms.decision.model;

import ai.intentoms.platform.ledger.Actor;

import java.util.Map;

/**
 * Per-invocation options (FR-DEC-07).
 *
 * @param versionOverride evaluate a specific definition version (replay, impact diff) instead of live/canary resolution
 * @param extraVars       additional CEL variables for rules and checks (bound under {@code ctx})
 */
public record DecisionOptions(String subjectType, String subjectId, String correlationId, Mode mode, Integer versionOverride, Actor actor, Map<String, Object> extraVars) {
    public DecisionOptions {
        mode = mode == null ? Mode.LIVE : mode;
        actor = actor == null ? Actor.system("decision-runtime") : actor;
        extraVars = extraVars == null ? Map.of() : Map.copyOf(extraVars);
    }

    public static DecisionOptions live(String subjectType, String subjectId, Actor actor) {
        return new DecisionOptions(subjectType, subjectId, null, Mode.LIVE, null, actor, Map.of());
    }

    public static DecisionOptions simulation(String subjectType, String subjectId, int version) {
        return new DecisionOptions(subjectType, subjectId, null, Mode.SIMULATION, version, Actor.system("simulation"), Map.of());
    }

    public DecisionOptions withMode(Mode m) { return new DecisionOptions(subjectType, subjectId, correlationId, m, versionOverride, actor, extraVars); }
    public DecisionOptions withVersion(Integer v) { return new DecisionOptions(subjectType, subjectId, correlationId, mode, v, actor, extraVars); }
}
