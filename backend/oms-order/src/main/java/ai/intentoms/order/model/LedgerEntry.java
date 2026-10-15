package ai.intentoms.order.model;

import ai.intentoms.platform.ledger.Event;

import java.time.Instant;
import java.util.Map;

/** Ledger event as the order view shows it (UI {@code LedgerEvent}). */
public record LedgerEntry(String id, String type, String aggregateType, String aggregateId, Instant at, String actor, Map<String, Object> data,
                          long version, String correlationId, String causationId) {
    public static LedgerEntry of(Event e) {
        return new LedgerEntry(e.id(), e.type(), e.aggregateType(), e.aggregateId(), e.occurredAt(), e.actor() == null ? null : e.actor().ref(), e.payload(), e.sequence(), e.correlationId(), e.causationId());
    }
}
