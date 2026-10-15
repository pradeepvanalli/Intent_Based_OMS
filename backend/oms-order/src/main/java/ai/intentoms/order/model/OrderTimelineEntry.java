package ai.intentoms.order.model;

import java.time.Instant;
import java.util.Map;

/**
 * One line of the merged order timeline (FR-CS-01): ledger events on the order aggregate, workflow steps/signals/timers
 * and decision records, sorted by time. {@code source} is {@code ledger} | {@code workflow} | {@code decision}.
 */
public record OrderTimelineEntry(Instant at, String kind, String name, String status, Map<String, Object> details, String source) {}
