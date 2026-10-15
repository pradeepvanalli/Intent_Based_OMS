package ai.intentoms.simulation.replay;

import java.time.Instant;
import java.util.List;

/**
 * Result of comparing the ledger-rebuilt order with the live projection (design principle 4). An empty
 * {@code differences} list means every projection (order header status, entity quantity buckets, active holds, intent,
 * chosen plan) agrees with the ledger.
 *
 * @param differences one entry per disagreement; {@code kind} is header_status | entity_state | entity_missing | hold |
 *                    intent | sourcing | lines
 */
public record ConsistencyReport(String orderId, boolean consistent, List<Difference> differences, RebuiltOrder rebuilt, Instant checkedAt) {
    public ConsistencyReport { differences = differences == null ? List.of() : List.copyOf(differences); }

    public record Difference(String kind, String entityId, Object rebuilt, Object live, String message) {}
}
