package ai.intentoms.learning.service;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.platform.ledger.Event;

import java.util.List;

/**
 * Turns one kind of ledger event into a typed outcome (OC-03). Every implementation is a Spring bean; the
 * {@link OutcomeObserver} subscribes to the union of their event types and attaches what they return to the episodes
 * of the event's order and of the event's own subject. Add a mapper for a new outcome source rather than editing the
 * observer.
 */
public interface OutcomeMapper {
    /** Exact ledger event types this mapper understands. */
    List<String> eventTypes();

    /** The outcome for this event, or null when this particular event carries none. */
    OutcomeData map(Event e);
}
