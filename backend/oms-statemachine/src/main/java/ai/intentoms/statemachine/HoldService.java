package ai.intentoms.statemachine;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.statemachine.model.Hold;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Typed hold overlay (FR-SM-07). Scope types: {@code order}, {@code order_line}, {@code return}. Writes {@code hold.placed}
 * / {@code hold.released} events on the scope's aggregate; expired holds are released by the sweeper with resolution
 * {@code expired}.
 */
public interface HoldService {
    /** @param ttl null = no expiry. {@code blocks} holds trigger names, target state ids or {@code "*"}. */
    Hold place(String type, String scopeType, String scopeId, Set<String> blocks, String reasonCode, String note, Duration ttl, Actor actor);

    Hold release(String holdId, String resolution, Actor actor);

    Hold get(String holdId);

    List<Hold> active(String scopeType, String scopeId);

    boolean blocks(String scopeType, String scopeId, String transitionTriggerOrState);

    /** Active holds on the scope (and, for a line scope, on its parent order) blocking any of the given tokens. */
    List<Hold> blocking(String scopeType, String scopeId, Collection<String> triggersOrStates);

    /** Release every expired hold across tenants; returns the number released. */
    int releaseExpired();
}
