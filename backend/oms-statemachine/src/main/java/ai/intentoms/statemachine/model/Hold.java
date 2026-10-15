package ai.intentoms.statemachine.model;

import java.time.Instant;
import java.util.Set;

/**
 * Typed hold overlay (FR-SM-07): fraud, payment, address, compliance, agent_verification, manual, ... A hold blocks the
 * transitions whose trigger name or target state id is in {@code blocksTransitions} ({@code "*"} blocks every
 * transition) on its scope (order, order_line, return); order holds also block the order's lines.
 */
public record Hold(String id, String type, String scopeType, String scopeId, Set<String> blocksTransitions, String reasonCode, String note,
                   Instant expiresAt, String placedBy, Instant placedAt, String resolvedBy, Instant resolvedAt, String resolution) {
    public Hold { blocksTransitions = blocksTransitions == null ? Set.of() : Set.copyOf(blocksTransitions); }

    public boolean active(Instant now) { return resolvedAt == null && (expiresAt == null || expiresAt.isAfter(now)); }

    public boolean blocks(String triggerOrState) {
        return blocksTransitions.contains("*") || blocksTransitions.contains(triggerOrState);
    }
}
