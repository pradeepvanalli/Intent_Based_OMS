package ai.intentoms.platform.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only, per-aggregate ordered event ledger with optimistic concurrency.
 * Exactly-once per order (NFR-06): callers append within the same DB transaction as their state change and pass the
 * expected version; a mismatch raises ConflictException and the whole transaction rolls back.
 */
public interface EventStore {
    /** Append events for an aggregate. expectedVersion = current sequence (0 for a new aggregate), or -1 to skip the check. */
    List<Event> append(String aggregateType, String aggregateId, long expectedVersion, List<NewEvent> events);

    default Event append(String aggregateType, String aggregateId, NewEvent event) {
        return append(aggregateType, aggregateId, -1, List.of(event)).get(0);
    }

    List<Event> load(String aggregateType, String aggregateId);

    List<Event> loadFrom(String aggregateType, String aggregateId, long fromSequenceExclusive);

    long currentVersion(String aggregateType, String aggregateId);

    Optional<Event> byId(String eventId);

    /** Global scan for projections/replay, ordered by global position (ascending). */
    List<Event> scan(long afterPosition, int limit, List<String> typePrefixes);

    /** Events for a tenant inside a time window (used by simulation and analytics). */
    List<Event> between(String aggregateType, Instant from, Instant to, List<String> types, int limit);
}
