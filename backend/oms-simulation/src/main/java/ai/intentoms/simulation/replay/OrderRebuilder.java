package ai.intentoms.simulation.replay;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rebuilds any order from the ledger alone and checks the live projection against it (design principle 4, FR-SIM-01).
 * {@link #rebuild} loads every event of aggregate {@code order/<id>} ({@link EventStore#load}) and folds it with
 * {@link LedgerFold}; {@link #check} then compares header status, per-entity quantity buckets, active holds, intent and
 * the chosen plan with {@link OrderRepository}, {@link StateMachine} and {@link HoldService}.
 */
@Service
public class OrderRebuilder {
    private static final Set<String> INACTIVE_GROUP = Set.of("rejected", "cancelled");

    private final EventStore events;
    private final OrderRepository orders;
    private final StateMachine stateMachine;
    private final HoldService holds;
    private final DecisionService decisions;
    private final TimeSource time;
    private final Metrics metrics;

    public OrderRebuilder(EventStore events, OrderRepository orders, StateMachine stateMachine, HoldService holds, DecisionService decisions, TimeSource time, Metrics metrics) {
        this.events = events;
        this.orders = orders;
        this.stateMachine = stateMachine;
        this.holds = holds;
        this.decisions = decisions;
        this.time = time;
        this.metrics = metrics;
    }

    /** The order as the ledger describes it; throws {@code NotFoundException} when the aggregate has no events. */
    public RebuiltOrder rebuild(String orderId) {
        return metrics.time("oms.simulation.rebuild", () -> {
            List<Event> ledger = events.load("order", orderId);
            if (ledger.isEmpty()) throw new NotFoundException("No ledger events for order " + orderId, Map.of("orderId", orderId));
            RebuiltOrder rebuilt = LedgerFold.fold(orderId, LedgerFold.ordered(ledger));
            List<String> recordIds = decisions.forSubject("order", orderId).stream().filter(r -> r.mode() == Mode.LIVE).map(DecisionRecord::id).toList();
            return rebuilt.withDecisions(recordIds);
        });
    }

    /** Rebuild and compare with the live projection. */
    public ConsistencyReport check(String orderId) {
        RebuiltOrder rebuilt = rebuild(orderId);
        List<ConsistencyReport.Difference> diffs = new ArrayList<>();
        Optional<Order> liveOrder = orders.find(orderId);
        if (liveOrder.isEmpty()) {
            diffs.add(new ConsistencyReport.Difference("order_missing", orderId, rebuilt.headerStatus(), null, "the ledger has events for the order but no orders row exists"));
            return report(rebuilt, diffs);
        }
        Order live = liveOrder.get();

        // Header status: the projected orders.status column vs the ledger's header entity.
        if (rebuilt.headerStatus() != null && live.status() != null && !rebuilt.headerStatus().equals(live.status())) {
            diffs.add(new ConsistencyReport.Difference("header_status", orderId, rebuilt.headerStatus(), live.status(), "orders.status disagrees with the ledger header state"));
        }

        // Line count: every live line must have a ledger entity and vice versa.
        Set<String> liveLineIds = new LinkedHashSet<>();
        live.lines().forEach(l -> liveLineIds.add(orderId + ":" + l.lineId()));
        Set<String> rebuiltLineIds = new LinkedHashSet<>();
        rebuilt.lines().forEach(e -> rebuiltLineIds.add(e.entityId()));
        if (!liveLineIds.equals(rebuiltLineIds)) {
            diffs.add(new ConsistencyReport.Difference("lines", orderId, rebuiltLineIds, liveLineIds, "line entities in the ledger differ from the order's lines"));
        }

        // Quantity buckets per entity.
        compareEntities(rebuilt, "order", List.of(orderId), diffs);
        compareEntities(rebuilt, "order_line", new ArrayList<>(rebuiltLineIds), diffs);

        // Holds: placed − released in the ledger vs the active rows, per scope.
        Map<String, Set<String>> scopes = new LinkedHashMap<>();
        scopes.computeIfAbsent("order", k -> new LinkedHashSet<>()).add(orderId);
        for (RebuiltOrder.HoldState h : rebuilt.holds()) if (h.scopeType() != null && h.scopeId() != null) scopes.computeIfAbsent(h.scopeType(), k -> new LinkedHashSet<>()).add(h.scopeId());
        Set<String> liveHoldIds = new LinkedHashSet<>();
        for (var sc : scopes.entrySet()) for (String id : sc.getValue()) for (Hold h : holds.active(sc.getKey(), id)) liveHoldIds.add(h.id());
        Set<String> rebuiltHoldIds = new LinkedHashSet<>();
        rebuilt.holds().forEach(h -> rebuiltHoldIds.add(h.holdId()));
        if (!rebuiltHoldIds.equals(liveHoldIds)) {
            diffs.add(new ConsistencyReport.Difference("hold", orderId, rebuiltHoldIds, liveHoldIds, "active holds differ (ledger placed − released vs hold table)"));
        }

        // Intent: only when the ledger recorded a resolution (explicit intents are stored at capture without an event).
        if (rebuilt.intent() != null && live.intent() != null && !rebuilt.intent().policy().equals(live.intent().policy())) {
            diffs.add(new ConsistencyReport.Difference("intent", orderId, rebuilt.intent().policy(), live.intent().policy(), "intent policy differs from the last order.intent_resolved"));
        }

        // Sourcing: the latest sourced/resourced plan must be referenced by an active fulfilment group.
        RebuiltOrder.SourcedPlan plan = rebuilt.latestPlan();
        if (plan != null) {
            Set<String> activePlanIds = new LinkedHashSet<>();
            for (FulfilmentGroup g : live.fulfilmentGroups()) if (g.status() == null || !INACTIVE_GROUP.contains(g.status().toLowerCase())) if (g.sourcingPlanId() != null) activePlanIds.add(g.sourcingPlanId());
            if (!activePlanIds.contains(plan.planId())) {
                diffs.add(new ConsistencyReport.Difference("sourcing", orderId, plan.planId(), activePlanIds, "no active fulfilment group references the last recorded plan"));
            }
        }
        return report(rebuilt, diffs);
    }

    private void compareEntities(RebuiltOrder rebuilt, String entityType, List<String> ids, List<ConsistencyReport.Difference> diffs) {
        if (ids.isEmpty()) return;
        Map<String, EntityStatus> live = new LinkedHashMap<>();
        for (EntityStatus s : stateMachine.statuses(entityType, ids)) live.put(s.entityId(), s);
        for (String id : ids) {
            RebuiltOrder.EntityState r = rebuilt.entities().get(id);
            EntityStatus l = live.get(id);
            if (r == null && l == null) continue;
            if (r == null || l == null) {
                diffs.add(new ConsistencyReport.Difference("entity_missing", id, r == null ? null : r.states(), l == null ? null : nonZero(l.qtyMap()),
                    r == null ? "entity exists live but has no ledger events" : "entity exists in the ledger but not in entity_state"));
                continue;
            }
            Map<String, Integer> lq = nonZero(l.qtyMap());
            Map<String, Integer> rq = new TreeMap<>(r.states());
            if (!lq.equals(rq)) diffs.add(new ConsistencyReport.Difference("entity_state", id, rq, lq, "quantity buckets differ"));
            if (r.modelVersion() != l.modelVersion() && r.modelVersion() > 0) {
                diffs.add(new ConsistencyReport.Difference("model_version", id, r.modelVersion(), l.modelVersion(), "pinned state-model version differs"));
            }
        }
    }

    private static Map<String, Integer> nonZero(Map<String, Integer> m) {
        Map<String, Integer> out = new TreeMap<>();
        m.forEach((k, v) -> { if (v != null && v > 0) out.put(k, v); });
        return out;
    }

    private ConsistencyReport report(RebuiltOrder rebuilt, List<ConsistencyReport.Difference> diffs) {
        metrics.count("oms.simulation.rebuild_checks", "consistent", String.valueOf(diffs.isEmpty()));
        return new ConsistencyReport(rebuilt.orderId(), diffs.isEmpty(), diffs, rebuilt, time.now());
    }
}
