package ai.intentoms.simulation.replay;

import ai.intentoms.platform.ledger.Event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure left fold of an order aggregate's ledger into a {@link RebuiltOrder} (design principle 4, FR-SIM-01). Understands
 * the event grammar every module writes on the {@code order} aggregate:
 * <ul>
 *   <li>{@code order.captured}: channel, order type, lines</li>
 *   <li>{@code order.state_initialized}: an entity (header or line) enters its model's Created state with {@code qty} units</li>
 *   <li>{@code order.transitioned}: quantity moves {@code from}→{@code to}; the {@code after} map is authoritative for the
 *       entity's buckets (header roll-ups carry {@code childStateQty})</li>
 *   <li>{@code hold.placed} / {@code hold.released}: holds are counted placed − released by hold id</li>
 *   <li>{@code order.intent_resolved}: the fulfilment intent (FR-CAP-06)</li>
 *   <li>{@code order.sourced} / {@code order.resourced}: the chosen plan (FR-SRC-03)</li>
 * </ul>
 * Everything else lands on the timeline only. The fold never touches the database, so it is unit-testable and can run
 * over any event list (live ledger, an export, a synthetic history).
 */
public final class LedgerFold {
    public static final String EVT_CAPTURED = "order.captured";
    public static final String EVT_STATE_INITIALIZED = "order.state_initialized";
    public static final String EVT_TRANSITIONED = "order.transitioned";
    public static final String EVT_HOLD_PLACED = "hold.placed";
    public static final String EVT_HOLD_RELEASED = "hold.released";
    public static final String EVT_INTENT = "order.intent_resolved";
    public static final String EVT_SOURCED = "order.sourced";
    public static final String EVT_RESOURCED = "order.resourced";

    private LedgerFold() {}

    private static final class EntityAcc {
        String entityType, modelId, initialState;
        int modelVersion, initialQty;
        final Map<String, Integer> states = new LinkedHashMap<>();
        String lastEntered;
    }

    public static RebuiltOrder fold(String orderId, List<Event> events) {
        String channel = null, orderType = null;
        Map<String, EntityAcc> entities = new LinkedHashMap<>();
        Map<String, RebuiltOrder.HoldState> activeHolds = new LinkedHashMap<>();
        int holdsPlaced = 0, holdsReleased = 0;
        RebuiltOrder.Intent intent = null;
        List<RebuiltOrder.SourcedPlan> plans = new ArrayList<>();
        List<RebuiltOrder.RecordedTransition> transitions = new ArrayList<>();
        List<RebuiltOrder.TimelineEntry> timeline = new ArrayList<>(events.size());
        Instant first = null, last = null;

        for (Event e : events) {
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            if (first == null || e.occurredAt().isBefore(first)) first = e.occurredAt();
            if (last == null || e.occurredAt().isAfter(last)) last = e.occurredAt();
            String summary;
            switch (e.type()) {
                case EVT_CAPTURED -> {
                    channel = str(p.get("channel"));
                    orderType = str(p.get("orderType"));
                    summary = "captured on " + channel + " as " + orderType;
                }
                case EVT_STATE_INITIALIZED -> {
                    String entityId = str(p.get("entityId"));
                    EntityAcc acc = entities.computeIfAbsent(entityId == null ? orderId : entityId, k -> new EntityAcc());
                    acc.entityType = str(p.get("entityType"));
                    acc.modelId = str(p.get("modelId"));
                    acc.modelVersion = integer(p.get("modelVersion"), 1);
                    acc.initialState = str(p.get("state"));
                    acc.initialQty = integer(p.get("qty"), 1);
                    acc.states.clear();
                    acc.states.put(acc.initialState, acc.initialQty);
                    acc.lastEntered = acc.initialState;
                    summary = acc.entityType + " " + entityId + " initialised in " + acc.initialState + " (" + acc.initialQty + ")";
                }
                case EVT_TRANSITIONED -> {
                    String entityId = str(p.get("entityId"));
                    EntityAcc acc = entities.computeIfAbsent(entityId == null ? orderId : entityId, k -> new EntityAcc());
                    if (acc.entityType == null) acc.entityType = str(p.get("entityType"));
                    if (acc.modelId == null) acc.modelId = str(p.get("modelId"));
                    if (acc.modelVersion == 0) acc.modelVersion = integer(p.get("modelVersion"), 1);
                    String from = str(p.get("from")), to = str(p.get("to")), trigger = str(p.get("trigger"));
                    int qty = integer(p.get("qty"), 1);
                    Map<String, Integer> before = intMap(p.get("before")), after = intMap(p.get("after"));
                    if (!after.isEmpty()) {
                        acc.states.clear();
                        after.forEach((s, q) -> { if (q > 0) acc.states.put(s, q); });
                    } else {
                        // Defensive: an event without buckets still moves qty from→to.
                        if (from != null) acc.states.merge(from, -qty, Integer::sum);
                        if (to != null) acc.states.merge(to, qty, Integer::sum);
                        acc.states.values().removeIf(q -> q <= 0);
                    }
                    acc.lastEntered = to;
                    transitions.add(new RebuiltOrder.RecordedTransition(acc.entityType, entityId, acc.modelId, acc.modelVersion, from, to, qty, trigger, before, after,
                        p.containsKey("childStateQty") ? intMap(p.get("childStateQty")) : null, e.occurredAt()));
                    summary = acc.entityType + " " + entityId + ": " + from + " -> " + to + " x" + qty + " on " + trigger;
                }
                case EVT_HOLD_PLACED -> {
                    holdsPlaced++;
                    String holdId = str(p.get("holdId"));
                    activeHolds.put(holdId, new RebuiltOrder.HoldState(holdId, str(p.get("type")), str(p.get("scopeType")), str(p.get("scopeId")), strSet(p.get("blocks")),
                        str(p.get("reasonCode")), e.occurredAt()));
                    summary = "hold " + str(p.get("type")) + " placed (" + str(p.get("reasonCode")) + ")";
                }
                case EVT_HOLD_RELEASED -> {
                    holdsReleased++;
                    activeHolds.remove(str(p.get("holdId")));
                    summary = "hold " + str(p.get("type")) + " released (" + str(p.get("resolution")) + ")";
                }
                case EVT_INTENT -> {
                    intent = new RebuiltOrder.Intent(str(p.get("policy")), dbl(p.get("confidence"), 0), str(p.get("source")), str(p.get("decisionRecordId")), e.occurredAt());
                    summary = "intent " + intent.policy() + " (" + intent.source() + ")";
                }
                case EVT_SOURCED, EVT_RESOURCED -> {
                    RebuiltOrder.SourcedPlan plan = plan(p, e.type(), e.occurredAt());
                    if (plan != null) plans.add(plan);
                    summary = plan == null ? e.type() : e.type() + " plan " + plan.planId() + " (" + plan.policy() + ", " + plan.packages() + " package(s))";
                }
                default -> summary = e.type();
            }
            timeline.add(new RebuiltOrder.TimelineEntry(e.sequence(), e.occurredAt(), e.type(), e.actor() == null ? null : e.actor().ref(), summary));
        }

        Map<String, RebuiltOrder.EntityState> out = new LinkedHashMap<>();
        for (var en : entities.entrySet()) {
            EntityAcc a = en.getValue();
            out.put(en.getKey(), new RebuiltOrder.EntityState(a.entityType, en.getKey(), a.modelId, a.modelVersion, a.initialState, a.initialQty, a.states, dominant(a.states, a.lastEntered)));
        }
        RebuiltOrder.EntityState header = out.get(orderId);
        String headerStatus = header == null ? null : header.dominantState();
        return new RebuiltOrder(orderId, channel, orderType, events.size(), first, last, headerStatus, out, new ArrayList<>(activeHolds.values()), holdsPlaced, holdsReleased, intent,
            plans, transitions, List.of(), timeline);
    }

    /** The state with the most quantity; ties resolved in favour of the most recently entered state. */
    public static String dominant(Map<String, Integer> states, String lastEntered) {
        String best = null;
        int bestQty = -1;
        for (var e : states.entrySet()) {
            int q = e.getValue();
            if (q > bestQty || (q == bestQty && e.getKey().equals(lastEntered))) { best = e.getKey(); bestQty = q; }
        }
        return best == null ? lastEntered : best;
    }

    @SuppressWarnings("unchecked")
    static RebuiltOrder.SourcedPlan plan(Map<String, Object> p, String type, Instant at) {
        String planId = str(p.get("planId"));
        if (planId == null || planId.isBlank()) return null;
        List<String> nodes = new ArrayList<>();
        int packages = 0;
        double onTime = 1.0;
        String eta = null;
        if (p.get("packages") instanceof List<?> l) {
            packages = l.size();
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> pk = (Map<String, Object>) m;
                String node = str(pk.get("nodeId"));
                if (node != null && !nodes.contains(node)) nodes.add(node);
                onTime = Math.min(onTime, dbl(pk.get("onTime"), 1.0));
                String pkEta = str(pk.get("eta"));
                if (pkEta != null && !"null".equals(pkEta) && (eta == null || pkEta.compareTo(eta) > 0)) eta = pkEta;
            }
        }
        Set<String> uniqueNodes = new LinkedHashSet<>(nodes);
        return new RebuiltOrder.SourcedPlan(planId, str(p.get("policy")), packages, new ArrayList<>(uniqueNodes), decimal(p.get("totalCost")),
            p.get("margin") == null ? null : dbl(p.get("margin"), 0), packages == 0 ? null : onTime, eta, str(p.get("decisionRecordId")), type, at);
    }

    static String str(Object o) { return o == null ? null : o.toString(); }

    static int integer(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return dflt;
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return dflt; }
    }

    static double dbl(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return dflt;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return dflt; }
    }

    static BigDecimal decimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        try { return new BigDecimal(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    static Map<String, Integer> intMap(Object o) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> raw) raw.forEach((k, v) -> { if (k != null && v != null) m.put(k.toString(), integer(v, 0)); });
        return m;
    }

    static Set<String> strSet(Object o) {
        Set<String> s = new LinkedHashSet<>();
        if (o instanceof java.util.Collection<?> c) for (Object x : c) if (x != null) s.add(x.toString());
        return s;
    }

    /** Helper for tests and callers composing synthetic histories: a stable, map-based ordering of events by sequence. */
    public static List<Event> ordered(List<Event> events) {
        List<Event> copy = new ArrayList<>(events);
        copy.sort((a, b) -> Long.compare(a.sequence(), b.sequence()));
        return copy;
    }
}
