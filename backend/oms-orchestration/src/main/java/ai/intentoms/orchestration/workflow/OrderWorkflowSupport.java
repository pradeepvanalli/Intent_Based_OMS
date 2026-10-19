package ai.intentoms.orchestration.workflow;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.finance.payment.PaymentStatus;
import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.inventory.model.ReservationLine;
import ai.intentoms.inventory.service.ReservationService;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionRequest;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.Tender;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers shared by the order workflows (FR-WF-01/02, UC-01..UC-07): state-machine transitions on lines, idempotent
 * reservations per fulfilment group, payment authorisation/capture, exception raising and ledger events. Every method
 * returns JSON-friendly values so callers can wrap them in {@code WorkflowContext.activity}.
 */
@Component
public class OrderWorkflowSupport {
    private static final Logger log = LoggerFactory.getLogger(OrderWorkflowSupport.class);
    public static final Actor ACTOR = Actor.system("workflow");
    static final Set<String> INACTIVE_GROUP = Set.of("rejected", "cancelled", "shipped", "delivered", "picked_up");
    static final Set<String> OPEN_LINE_STATES = Set.of("created", "awaiting_sourcing", "released", "picking", "packed", "picked", "ready_for_pickup", "backordered");

    private final OrderService orders;
    private final StateMachine machine;
    private final HoldService holds;
    private final ReservationService reservations;
    private final PaymentService payments;
    private final ExceptionService exceptions;
    private final EventStore events;

    public OrderWorkflowSupport(OrderService orders, StateMachine machine, HoldService holds, ReservationService reservations, PaymentService payments,
                                ExceptionService exceptions, EventStore events) {
        this.orders = orders;
        this.machine = machine;
        this.holds = holds;
        this.reservations = reservations;
        this.payments = payments;
        this.exceptions = exceptions;
        this.events = events;
    }

    public OrderService orders() { return orders; }
    public StateMachine machine() { return machine; }
    public HoldService holds() { return holds; }
    public ReservationService reservations() { return reservations; }
    public PaymentService payments() { return payments; }
    public ExceptionService exceptions() { return exceptions; }

    // ------------------------------------------------------------------ state machine

    public static String lineEntity(String orderId, String lineId) { return orderId + ":" + lineId; }

    /** Fires a trigger on a line; {@code qty} null moves every unit in a matching state. Never throws; returns a JSON-friendly summary. */
    public Map<String, Object> fireLine(String orderId, String lineId, String trigger, Integer qty, Map<String, Object> vars) {
        TransitionResult r = machine.fire(new TransitionRequest("order_line", lineEntity(orderId, lineId), trigger, qty, null, vars == null ? Map.of() : vars, ACTOR, orderId, null));
        return transitionMap(lineId, trigger, r);
    }

    /** Fires a trigger on the order header entity. */
    public Map<String, Object> fireHeader(String orderId, String trigger, Map<String, Object> vars) {
        TransitionResult r = machine.fire(new TransitionRequest("order", orderId, trigger, null, null, vars == null ? Map.of() : vars, ACTOR, orderId, null));
        return transitionMap(null, trigger, r);
    }

    /** Fires {@code trigger} on every line of the order; returns one summary per line. */
    public List<Map<String, Object>> fireAllLines(Order order, String trigger, Map<String, Object> vars) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrderLine l : order.lines()) out.add(fireLine(order.id(), l.lineId(), trigger, null, vars));
        return out;
    }

    public EntityStatus lineStatus(String orderId, String lineId) { return machine.status("order_line", lineEntity(orderId, lineId)); }

    public Map<String, EntityStatus> lineStatuses(Order order) {
        Map<String, EntityStatus> out = new LinkedHashMap<>();
        for (OrderLine l : order.lines()) out.put(l.lineId(), lineStatus(order.id(), l.lineId()));
        return out;
    }

    public int qtyIn(Order order, String lineId, String state) { return lineStatus(order.id(), lineId).qtyIn(state); }

    /** Units of a line not yet terminal (cancelled/delivered/picked_up) and not shipped. */
    public static int openQty(EntityStatus st) {
        int n = 0;
        for (var b : st.quantities()) if (OPEN_LINE_STATES.contains(b.state())) n += b.qty();
        return n;
    }

    /** True when every unit of every line is in one of {@code states}. */
    public boolean allLinesIn(Order order, Set<String> states) {
        for (OrderLine l : order.lines()) {
            EntityStatus st = lineStatus(order.id(), l.lineId());
            for (var b : st.quantities()) if (b.qty() > 0 && !states.contains(b.state())) return false;
        }
        return true;
    }

    public boolean anyLineIn(Order order, String state) {
        for (OrderLine l : order.lines()) if (qtyIn(order, l.lineId(), state) > 0) return true;
        return false;
    }

    public static Map<String, Object> transitionMap(String lineId, String trigger, TransitionResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (lineId != null) m.put("lineId", lineId);
        m.put("trigger", trigger);
        m.put("applied", r.applied());
        m.put("from", r.fromState());
        m.put("to", r.toState());
        m.put("qty", r.qty());
        if (!r.blockedBy().isEmpty()) m.put("blockedBy", r.blockedBy());
        return m;
    }

    // ------------------------------------------------------------------ holds

    public List<Hold> holdsBlocking(String orderId, String token) {
        return holds.active("order", orderId).stream().filter(h -> h.blocks(token)).toList();
    }

    public List<Hold> holdsOfType(String orderId, String type) {
        return holds.active("order", orderId).stream().filter(h -> type.equals(h.type())).toList();
    }

    // ------------------------------------------------------------------ fulfilment groups

    public static List<FulfilmentGroup> activeGroups(Order order) {
        return order.fulfilmentGroups().stream().filter(g -> g.status() == null || !INACTIVE_GROUP.contains(g.status().toLowerCase())).toList();
    }

    public static FulfilmentGroup group(Order order, String groupId) {
        return order.fulfilmentGroups().stream().filter(g -> g.id().equals(groupId)).findFirst().orElse(null);
    }

    /** Sets the status of the given fulfilment groups and appends {@code event} (null = none). */
    public Order markGroups(String orderId, Set<String> groupIds, String status, NewEvent event) {
        return orders.update(orderId, o -> {
            List<FulfilmentGroup> groups = new ArrayList<>();
            for (FulfilmentGroup g : o.fulfilmentGroups()) {
                groups.add(groupIds.contains(g.id()) ? new FulfilmentGroup(g.id(), g.nodeId(), g.policy(), g.allocations(), g.promiseDate(), g.onTimeProbability(), g.shipMethod(),
                    g.estimatedCost(), status, g.sourcingPlanId(), g.decisionRecordId(), g.explain()) : g);
            }
            return o.withFulfilmentGroups(groups);
        }, event);
    }

    // ------------------------------------------------------------------ reservations (FR-INV-02)

    /**
     * Reserves what the active fulfilment groups need, net of reservations already ACTIVE for the order (idempotent on
     * replay). Returns {@code {ok, reserved:[ids], shortage:{sku,nodeId,available,requested}}}; never throws.
     */
    public Map<String, Object> reserveForGroups(Order order) {
        Map<String, Integer> needed = new LinkedHashMap<>();
        for (FulfilmentGroup g : activeGroups(order)) {
            if (g.nodeId() == null) continue;
            for (FulfilmentGroup.Allocation a : g.allocations()) needed.merge(a.sku() + "|" + g.nodeId(), a.qty(), Integer::sum);
        }
        for (Reservation r : reservations.forRef(order.id())) {
            if (!r.active()) continue;
            needed.computeIfPresent(r.sku() + "|" + r.nodeId(), (k, v) -> v - r.qty());
        }
        List<ReservationLine> lines = new ArrayList<>();
        needed.forEach((k, v) -> { if (v > 0) { String[] p = k.split("\\|", 2); lines.add(new ReservationLine(p[0], p[1], v)); } });
        Map<String, Object> out = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            out.put("ok", true);
            out.put("reserved", List.of());
            out.put("alreadyReserved", true);
            return out;
        }
        // A shortage is expected here (another order took the stock after this one was planned). It comes back as a
        // result, never as an exception through the inner transactional proxy, so this step's transaction stays
        // committable and the workflow re-sources instead of failing and compensating the order (audit A1).
        ReservationService.ReserveAttempt attempt = reservations.tryReserveAll(lines, Reservation.SOURCE_ORDER, order.id(), null);
        if (attempt.ok()) {
            out.put("ok", true);
            out.put("reserved", attempt.reserved().stream().map(Reservation::id).toList());
            out.put("lines", lines.stream().<Map<String, Object>>map(l -> Map.of("sku", l.sku(), "nodeId", l.nodeId(), "qty", l.qty())).toList());
        } else {
            out.put("ok", false);
            out.put("error", "insufficient_atp");
            out.put("shortage", new LinkedHashMap<>(attempt.shortage()));
        }
        return out;
    }

    /** Releases every ACTIVE reservation of the order (compensation, cancel, store reject). */
    public List<String> releaseReservations(String orderId, String reason) {
        List<String> released = new ArrayList<>();
        for (Reservation r : reservations.forRef(orderId)) {
            if (!r.active()) continue;
            reservations.release(r.id(), reason);
            released.add(r.id());
        }
        return released;
    }

    /** Releases ACTIVE reservations at one node for the given skus (a rejected package). */
    public List<String> releaseReservationsAt(String orderId, String nodeId, Set<String> skus, String reason) {
        List<String> released = new ArrayList<>();
        for (Reservation r : reservations.forRef(orderId)) {
            if (!r.active() || (nodeId != null && !nodeId.equals(r.nodeId())) || (skus != null && !skus.isEmpty() && !skus.contains(r.sku()))) continue;
            reservations.release(r.id(), reason);
            released.add(r.id());
        }
        return released;
    }

    /**
     * Consumes reservations covered by a shipment: for each (sku, node) shipped, ACTIVE reservations whose quantity is
     * covered by the cumulative shipped quantity are consumed (on-hand decremented, FR-INV-02).
     *
     * @param shippedSoFar cumulative shipped units per {@code sku|node} including this shipment
     */
    public List<String> consumeReservations(String orderId, String nodeId, Map<String, Integer> shippedSoFar) {
        List<String> consumed = new ArrayList<>();
        Map<String, Integer> budget = new LinkedHashMap<>(shippedSoFar);
        for (Reservation r : reservations.forRef(orderId)) {
            if (!r.active() || !nodeId.equals(r.nodeId())) continue;
            int left = budget.getOrDefault(r.sku() + "|" + r.nodeId(), 0);
            if (left >= r.qty()) {
                reservations.consume(r.id());
                consumed.add(r.id());
                budget.put(r.sku() + "|" + r.nodeId(), left - r.qty());
            }
        }
        return consumed;
    }

    // ------------------------------------------------------------------ payments (FR-FIN-01)

    /** Authorises every tender not yet authorised; returns one summary per tender ({@code status}, {@code paymentId}, {@code failureCode}). */
    public List<Map<String, Object>> authorizeTenders(Order order) {
        List<Payment> existing = payments.forOrder(order.id());
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> mandate = mandate(order);
        for (Tender t : order.tenders()) {
            if (t.amount() == null || !t.amount().isPositive()) continue;
            Payment already = existing.stream().filter(p -> p.status() != PaymentStatus.FAILED && p.status() != PaymentStatus.VOIDED && tokenMatches(p, t)).findFirst().orElse(null);
            Payment p = already != null ? already : payments.authorize(order.id(), t.amount(), t.token(), t.type(), mandate);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenderType", t.type());
            m.put("paymentId", p.id());
            m.put("status", p.status().name());
            m.put("amount", p.amountAuthorized().amount());
            m.put("replayed", already != null);
            if (p.failureCode() != null) m.put("failureCode", p.failureCode());
            out.add(m);
        }
        return out;
    }

    private static boolean tokenMatches(Payment p, Tender t) {
        return t.token() != null && t.token().equals(p.tenderToken()) && (t.type() == null || t.type().equalsIgnoreCase(p.tenderType()));
    }

    static Map<String, Object> mandate(Order order) {
        if (order.provenance() == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        if (order.provenance().agentId() != null) m.put("agentId", order.provenance().agentId());
        if (order.provenance().mandateId() != null) m.put("mandateId", order.provenance().mandateId());
        if (order.provenance().protocol() != null) m.put("protocol", order.provenance().protocol());
        if (order.provenance().spendLimit() != null) m.put("spendLimit", order.provenance().spendLimit().amount());
        if (order.provenance().instructionText() != null) m.put("instructionText", order.provenance().instructionText());
        return m;
    }

    /** Uncaptured authorised funds across open payments. */
    public Money uncaptured(String orderId, String currency) {
        Money sum = Money.zero(currency);
        for (Payment p : payments.forOrder(orderId)) if (p.status().isOpen()) sum = sum.add(p.uncaptured());
        return sum;
    }

    public Money captured(String orderId, String currency) {
        Money sum = Money.zero(currency);
        for (Payment p : payments.forOrder(orderId)) if (p.amountCaptured() != null && p.amountCaptured().currency().equals(currency)) sum = sum.add(p.amountCaptured());
        return sum;
    }

    /**
     * Captures the value of the units just shipped (price + tax share), capped at the uncaptured authorisation; when
     * {@code finalShipment} the whole remaining authorisation is captured so shipping and fees are collected too (FR-FIN-01).
     */
    public Map<String, Object> captureForShipment(Order order, Map<String, Integer> shippedQtyByLine, boolean finalShipment, String reason) {
        Money value = Money.zero(order.currency());
        for (OrderLine l : order.lines()) {
            int q = shippedQtyByLine.getOrDefault(l.lineId(), 0);
            if (q <= 0 || l.qty() <= 0) continue;
            Money lineTotal = l.extendedPrice().add(l.tax() == null ? Money.zero(order.currency()) : l.tax());
            value = value.add(lineTotal.multiply(BigDecimal.valueOf(q).divide(BigDecimal.valueOf(l.qty()), 6, java.math.RoundingMode.HALF_EVEN)));
        }
        Money open = uncaptured(order.id(), order.currency());
        Money amount = finalShipment ? open : value.min(open);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", value.amount());
        out.put("uncapturedBefore", open.amount());
        if (!amount.isPositive()) {
            out.put("captured", BigDecimal.ZERO);
            out.put("skipped", "nothing to capture");
            return out;
        }
        List<Payment> touched = payments.capture(order.id(), amount, reason);
        out.put("captured", amount.amount());
        out.put("payments", touched.stream().<Map<String, Object>>map(p -> Map.of("paymentId", p.id(), "status", p.status().name(), "amountCaptured", p.amountCaptured().amount())).toList());
        out.put("failed", touched.stream().anyMatch(p -> p.status() == PaymentStatus.FAILED));
        return out;
    }

    public Map<String, Object> voidAuthorization(String orderId, String reason) {
        payments.voidAuthorization(orderId, reason);
        return Map.of("voided", true, "reason", reason);
    }

    // ------------------------------------------------------------------ exceptions (FR-MON-03)

    /** Raises (or classifies) an exception for the order; never throws so a monitoring hiccup cannot fail the workflow. */
    public Map<String, Object> raise(String source, String code, String message, String orderId, String lineId, String nodeId, Map<String, Object> details, String type) {
        return raise(source, code, message, orderId, lineId, nodeId, details, type, null);
    }

    /**
     * {@link #raise} with an explicit playbook: {@code ""} records the exception without running any playbook (used when the
     * workflow itself owns the remediation, e.g. the margin-floor approval task), null keeps the taxonomy default.
     */
    public Map<String, Object> raise(String source, String code, String message, String orderId, String lineId, String nodeId, Map<String, Object> details, String type, String playbook) {
        try {
            ExceptionRecord e = exceptions.raise(new ExceptionRequest(source, code, message, orderId, lineId, nodeId, details == null ? Map.of() : details), type, null, playbook);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exceptionId", e.id());
            m.put("type", e.type());
            m.put("queue", e.queue());
            m.put("playbook", e.playbook() == null ? "" : e.playbook());
            if (e.taskId() != null) m.put("taskId", e.taskId());
            return m;
        } catch (RuntimeException ex) {
            log.warn("Could not raise exception {}/{} for order {}: {}", source, code, orderId, ex.toString());
            return Map.of("error", String.valueOf(ex.getMessage()));
        }
    }

    public void resolveException(String exceptionId, String resolution, String note) {
        if (exceptionId == null) return;
        try { exceptions.resolve(exceptionId, resolution, note); } catch (RuntimeException e) { log.debug("Exception {} not resolved: {}", exceptionId, e.toString()); }
    }

    // ------------------------------------------------------------------ ledger

    public void appendOrderEvent(String orderId, String type, Map<String, Object> payload) {
        events.append("order", orderId, new NewEvent(type, payload, ACTOR, null, orderId, Map.of()));
    }

    // ------------------------------------------------------------------ misc

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOfMaps(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : l) if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    public static int intOf(Object o, int dflt) { return o instanceof Number n ? n.intValue() : o == null ? dflt : Integer.parseInt(o.toString()); }
    public static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
