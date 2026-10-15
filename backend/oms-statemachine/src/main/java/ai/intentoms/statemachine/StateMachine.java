package ai.intentoms.statemachine;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.TransitionDef;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The configurable statechart engine (FR-SM-01..03, FR-SM-05, FR-SM-07, FR-SM-08, FR-SM-11). Entities are addressed by
 * ({@code entityType}, {@code entityId}); line ids are {@code "<orderId>:<lineId>"} so their ledger events land on the
 * order aggregate.
 */
public interface StateMachine {
    /**
     * Put an entity into the model's Created state with {@code qty} units (1 for non quantity-level models) under the given
     * or latest version. Writes a {@code <aggregate>.state_initialized} event and schedules the SLA timer of the initial
     * state if it has one. Throws {@code ConflictException} when the entity already exists.
     */
    EntityStatus initialize(String entityType, String entityId, String modelId, Integer version, int qty, Map<String, Object> attributes, Actor actor);

    /**
     * Fire a trigger: locks the entity row, matches transitions from the current state(s), checks holds, the transition
     * permission (api.* triggers, user/agent actors only) and CEL guards, moves quantity, appends
     * {@code <aggregate>.transitioned}, maintains SLA timers, rolls the header up and publishes {@code TransitionApplied}.
     * A non-matching request returns {@code applied=false} with reasons unless {@code vars.strict=true}.
     */
    TransitionResult fire(TransitionRequest req);

    EntityStatus status(String entityType, String entityId);

    List<EntityStatus> statuses(String entityType, Collection<String> entityIds);

    /** Transitions currently fireable (holds, permission and guards evaluated with {@code vars}). */
    List<TransitionDef> available(String entityType, String entityId, Map<String, Object> vars);

    /** Header state derived from child quantities by the model's roll-up rules (FR-SM-05). */
    String rollup(String modelId, int version, Map<String, Integer> childStateQty);
}
