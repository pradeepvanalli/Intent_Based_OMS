package ai.intentoms.statemachine.model;

import ai.intentoms.platform.ledger.Actor;

import java.util.Map;

/**
 * Request to fire a trigger against an entity (FR-SM-03). {@code qty} null moves everything in the matching source
 * state(s); {@code fromState} null means any state currently holding quantity. {@code vars} are bound as CEL roots
 * for guards (plus {@code tenant} and {@code state} supplied by the engine); the reserved key {@code strict=true}
 * makes a non-matching request throw {@code InvalidTransitionException} instead of returning {@code applied=false}.
 * {@code actor} null means the current principal.
 */
public record TransitionRequest(String entityType, String entityId, String trigger, Integer qty, String fromState,
                                Map<String, Object> vars, Actor actor, String correlationId, String causationId) {
    public TransitionRequest {
        vars = vars == null ? Map.of() : Map.copyOf(vars);
    }

    public static TransitionRequest of(String entityType, String entityId, String trigger, Map<String, Object> vars, Actor actor) {
        return new TransitionRequest(entityType, entityId, trigger, null, null, vars, actor, null, null);
    }

    public boolean strict() { return Boolean.TRUE.equals(vars.get("strict")) || "true".equals(String.valueOf(vars.get("strict"))); }
}
