package ai.intentoms.simulation.replay;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An order rebuilt purely from its ledger events (design principle 4 "the ledger is the truth", FR-SIM-01). Nothing in
 * here is read from the {@code orders}, {@code entity_state} or {@code hold} tables: it is what the ledger alone says the
 * order looks like, so it can be compared with the live projection ({@link ConsistencyReport}).
 *
 * @param entities          every state-machine entity on the aggregate (header + lines) with its quantity by state
 * @param holds             holds placed and not yet released
 * @param sourcedPlans      every {@code order.sourced} / {@code order.resourced} plan, oldest first
 * @param transitions       every recorded transition in ledger order (input to a proposed state-model replay)
 * @param decisionRecordIds LIVE decision records attached to the order subject (filled by {@link OrderRebuilder})
 */
public record RebuiltOrder(
    String orderId,
    String channel,
    String orderType,
    int eventsFolded,
    Instant firstEventAt,
    Instant lastEventAt,
    String headerStatus,
    Map<String, EntityState> entities,
    List<HoldState> holds,
    int holdsPlaced,
    int holdsReleased,
    Intent intent,
    List<SourcedPlan> sourcedPlans,
    List<RecordedTransition> transitions,
    List<String> decisionRecordIds,
    List<TimelineEntry> timeline
) {
    public RebuiltOrder {
        entities = entities == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(entities));
        holds = holds == null ? List.of() : List.copyOf(holds);
        sourcedPlans = sourcedPlans == null ? List.of() : List.copyOf(sourcedPlans);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        decisionRecordIds = decisionRecordIds == null ? List.of() : List.copyOf(decisionRecordIds);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    /**
     * One state-machine entity as the ledger sees it.
     *
     * @param states        quantity per state (zero buckets removed)
     * @param dominantState the state holding the most quantity; ties go to the most recently entered state
     */
    public record EntityState(String entityType, String entityId, String modelId, int modelVersion, String initialState, int initialQty,
                              Map<String, Integer> states, String dominantState) {
        public EntityState {
            states = states == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(states));
        }
        public int totalQty() { int n = 0; for (int q : states.values()) n += q; return n; }
        public boolean isLine() { return entityType != null && entityType.endsWith("_line"); }
    }

    public record HoldState(String holdId, String type, String scopeType, String scopeId, Set<String> blocks, String reasonCode, Instant placedAt) {
        public HoldState { blocks = blocks == null ? Set.of() : Set.copyOf(blocks); }
    }

    public record Intent(String policy, double confidence, String source, String decisionRecordId, Instant at) {}

    /** Summary of a plan as the {@code order.sourced} payload records it (FR-SRC-03). */
    public record SourcedPlan(String planId, String policy, int packages, List<String> nodeIds, BigDecimal totalCost, Double marginRatio, Double onTimeProbability,
                              String latestEta, String decisionRecordId, String eventType, Instant at) {
        public SourcedPlan { nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds); }
    }

    /** One {@code <aggregate>.transitioned} event; {@code childStateQty} is present on header roll-ups only. */
    public record RecordedTransition(String entityType, String entityId, String modelId, int modelVersion, String from, String to, int qty, String trigger,
                                     Map<String, Integer> before, Map<String, Integer> after, Map<String, Integer> childStateQty, Instant at) {
        public RecordedTransition {
            before = before == null ? Map.of() : Map.copyOf(before);
            after = after == null ? Map.of() : Map.copyOf(after);
        }
        public boolean isRollup() { return "rollup".equals(trigger); }
    }

    public record TimelineEntry(long sequence, Instant at, String type, String actor, String summary) {}

    public EntityState header() { return entities.get(orderId); }

    public List<EntityState> lines() { return entities.values().stream().filter(EntityState::isLine).toList(); }

    /** Most recent sourcing outcome, or null when the order was never sourced. */
    public SourcedPlan latestPlan() { return sourcedPlans.isEmpty() ? null : sourcedPlans.get(sourcedPlans.size() - 1); }

    public RebuiltOrder withDecisions(List<String> recordIds) {
        return new RebuiltOrder(orderId, channel, orderType, eventsFolded, firstEventAt, lastEventAt, headerStatus, entities, holds, holdsPlaced, holdsReleased, intent,
            sourcedPlans, transitions, recordIds, timeline);
    }
}
