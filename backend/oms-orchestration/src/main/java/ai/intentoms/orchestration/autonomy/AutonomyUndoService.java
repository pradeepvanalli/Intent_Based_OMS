package ai.intentoms.orchestration.autonomy;

import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.orchestration.actions.ActionSupport;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.statemachine.HoldService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverses an undoable {@link AutonomousAction} (Agentic Control Tower §5): {@code re_source} re-evaluates sourcing
 * away from the node the autonomous action moved to; {@code re_promise} restores the promise dates recorded before
 * the action ran; {@code release_hold} re-places the hold with its original type and reason. {@code retry_payment}
 * and {@code notify_customer} are not meaningfully reversible and are rejected outright (never attempted here).
 * When a genuine reverse is not feasible — most commonly because the order has since shipped — this throws
 * {@link ConflictException} naming exactly why, rather than silently marking the action undone.
 */
@Service
public class AutonomyUndoService {
    private static final Set<String> SHIPPED_STATUSES = Set.of("shipped", "delivered", "picked_up");

    private final AutonomousActionRepository actions;
    private final OrderService orders;
    private final SourcingService sourcing;
    private final HoldService holds;
    private final EventStore events;
    private final TimeSource time;

    public AutonomyUndoService(AutonomousActionRepository actions, OrderService orders, SourcingService sourcing, HoldService holds, EventStore events, TimeSource time) {
        this.actions = actions;
        this.orders = orders;
        this.sourcing = sourcing;
        this.holds = holds;
        this.events = events;
        this.time = time;
    }

    @Transactional
    public AutonomousAction undo(String actionId, String reason, Actor actor) {
        AutonomousAction action = actions.get(actionId);
        if (action.isUndone()) throw new ConflictException("Autonomous action " + actionId + " was already undone at " + action.undoneAt(), Map.of("actionId", actionId));
        if (!AutonomousAction.OUTCOME_EXECUTED.equals(action.outcome())) throw new ConflictException("Autonomous action " + actionId + " was not executed; there is nothing to undo", Map.of("outcome", action.outcome()));
        AutonomousActionType type = AutonomousActionType.fromCode(action.actionTaken());
        if (!type.undoable()) throw new ValidationException(type.code() + " actions are not meaningfully reversible and cannot be undone");

        Order order = orders.get(action.orderId());
        switch (type) {
            case RE_SOURCE -> undoReSource(order, action, actor);
            case RE_PROMISE -> undoRePromise(order, action, actor);
            case RELEASE_HOLD -> undoReleaseHold(order, action, actor);
            default -> throw new ValidationException(type.code() + " has no undo implementation");
        }

        Instant now = time.now();
        actions.markUndone(actionId, now, actor.ref(), reason);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionId", actionId);
        payload.put("orderId", order.id());
        payload.put("actionTaken", action.actionTaken());
        payload.put("reason", reason);
        events.append("order", order.id(), new NewEvent("autonomy.action_undone", payload, actor));
        return actions.get(actionId);
    }

    /** Re-sources away from the node the autonomous action moved to, letting the ranking fall back to the next best (possibly the original) node. */
    private void undoReSource(Order order, AutonomousAction action, Actor actor) {
        String currentNodeId = ActionSupport.activeGroups(order, null).stream().findFirst().map(FulfilmentGroup::nodeId).orElse(null);
        requireNotShipped(order, currentNodeId, action.id());
        if (currentNodeId == null) throw new ConflictException("Order " + order.id() + " has no active fulfilment group to re-source away from", Map.of("orderId", order.id()));
        sourcing.resource(order.id(), currentNodeId, "autonomy_undo:" + action.id(), actor);
    }

    /** Restores the promise dates the {@code re_promise} action recorded as "old" before it ran. */
    @SuppressWarnings("unchecked")
    private void undoRePromise(Order order, AutonomousAction action, Actor actor) {
        Object raw = action.details().get("groups");
        if (!(raw instanceof List<?> rawGroups) || rawGroups.isEmpty()) {
            throw new ConflictException("No prior promise was recorded for autonomous action " + action.id() + "; cannot undo", Map.of("actionId", action.id()));
        }
        Map<String, LocalDate> priorByGroup = new LinkedHashMap<>();
        for (Object o : rawGroups) {
            if (!(o instanceof Map<?, ?> g)) continue;
            Object groupId = g.get("groupId");
            Object oldPromise = g.get("oldPromise");
            if (groupId == null || oldPromise == null) continue;
            try { priorByGroup.put(String.valueOf(groupId), LocalDate.parse(String.valueOf(oldPromise))); } catch (DateTimeParseException ignored) { }
        }
        if (priorByGroup.isEmpty()) throw new ConflictException("No usable prior promise date was recorded for autonomous action " + action.id(), Map.of("actionId", action.id()));
        for (FulfilmentGroup g : order.fulfilmentGroups()) {
            if (priorByGroup.containsKey(g.id()) && SHIPPED_STATUSES.contains(String.valueOf(g.status()).toLowerCase(java.util.Locale.ROOT))) {
                throw new ConflictException("Cannot undo: fulfilment group " + g.id() + " on order " + order.id() + " has already shipped", Map.of("orderId", order.id(), "groupId", g.id()));
            }
        }
        List<FulfilmentGroup> updated = new ArrayList<>();
        for (FulfilmentGroup g : order.fulfilmentGroups()) {
            LocalDate prior = priorByGroup.get(g.id());
            updated.add(prior == null ? g : new FulfilmentGroup(g.id(), g.nodeId(), g.policy(), g.allocations(), prior, g.onTimeProbability(), g.shipMethod(), g.estimatedCost(), g.status(), g.sourcingPlanId(), g.decisionRecordId(), g.explain()));
        }
        orders.update(order.id(), o -> o.withFulfilmentGroups(updated), new NewEvent("order.repromise_undone", Map.of("actionId", action.id(), "groups", List.copyOf(priorByGroup.keySet())), actor));
    }

    /** Re-places the hold with the type, scope and reason recorded when the autonomous {@code release_hold} action released it. */
    private void undoReleaseHold(Order order, AutonomousAction action, Actor actor) {
        Map<String, Object> d = action.details();
        String holdType = asString(d.get("holdType"));
        String scopeType = asString(d.get("scopeType"));
        String scopeId = asString(d.get("scopeId"));
        String reasonCode = asString(d.get("reasonCode"));
        if (holdType == null || scopeType == null || scopeId == null) {
            throw new ConflictException("No prior hold details were recorded for autonomous action " + action.id() + "; cannot undo", Map.of("actionId", action.id()));
        }
        boolean shipped = order.fulfilmentGroups().stream().anyMatch(g -> SHIPPED_STATUSES.contains(String.valueOf(g.status()).toLowerCase(java.util.Locale.ROOT)));
        if (shipped && "order".equals(scopeType)) throw new ConflictException("Cannot undo: order " + order.id() + " has already shipped", Map.of("orderId", order.id()));
        Object rawBlocks = d.get("blocks");
        Set<String> blocks = rawBlocks instanceof List<?> l ? l.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of("release");
        holds.place(holdType, scopeType, scopeId, blocks, reasonCode == null ? "autonomy_undo" : reasonCode, "Re-placed by autonomy undo of " + action.id(), null, actor);
    }

    private void requireNotShipped(Order order, String nodeId, String actionId) {
        boolean shipped = order.fulfilmentGroups().stream().anyMatch(g -> (nodeId == null || nodeId.equals(g.nodeId())) && SHIPPED_STATUSES.contains(String.valueOf(g.status()).toLowerCase(java.util.Locale.ROOT)));
        if (shipped) throw new ConflictException("Cannot undo autonomous action " + actionId + ": the order has already shipped", Map.of("orderId", order.id()));
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }
}
