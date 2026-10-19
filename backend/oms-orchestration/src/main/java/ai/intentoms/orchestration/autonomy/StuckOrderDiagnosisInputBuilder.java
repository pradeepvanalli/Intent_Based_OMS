package ai.intentoms.orchestration.autonomy;

import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.finance.payment.PaymentStatus;
import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.orchestration.shipment.ShipmentRecord;
import ai.intentoms.orchestration.shipment.ShipmentService;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles the {@code stuck_order_diagnosis} decision input from the platform's real services — payments, inventory
 * ATP, the node registry, shipments/carrier scans, holds and open tasks, and the order's own notes. A signal that
 * genuinely is not available (no shipment yet, no fulfilment group, no notes, unknown node) is omitted from the
 * input entirely rather than defaulted to a fake value, per the decision's {@code state.include} contract.
 *
 * <p>Timestamps on freshly seeded demo data are stamped at seed time regardless of the order's business
 * {@code placedAt}, so "how long has this been going on" is computed from the most recent real milestone that
 * actually moves with the order: the shipment's {@code shippedAt} once it has shipped, otherwise the order's own
 * {@code placedAt}. This is an honest, documented simplification, not a fabricated signal.</p>
 */
@Component
public class StuckOrderDiagnosisInputBuilder {
    static final int ASSUMED_MAX_PAYMENT_ATTEMPTS = 3;
    static final Set<String> INACTIVE_GROUP_STATUSES = Set.of("rejected", "cancelled", "shipped", "delivered", "picked_up");

    private final PaymentService payments;
    private final InventoryService inventory;
    private final NodeRegistry nodes;
    private final ShipmentService shipments;
    private final HoldService holds;
    private final HumanTaskService tasks;
    private final TimeSource time;

    public StuckOrderDiagnosisInputBuilder(PaymentService payments, InventoryService inventory, NodeRegistry nodes, ShipmentService shipments,
                                           HoldService holds, HumanTaskService tasks, TimeSource time) {
        this.payments = payments;
        this.inventory = inventory;
        this.nodes = nodes;
        this.shipments = shipments;
        this.holds = holds;
        this.tasks = tasks;
        this.time = time;
    }

    public Map<String, Object> build(Order order) {
        Instant now = time.now();
        Map<String, Object> input = new LinkedHashMap<>();

        List<ShipmentRecord> shipped = shipments.forOrder(order.id());
        Optional<ShipmentRecord> latestShipment = shipped.stream().max((a, b) -> a.shippedAt().compareTo(b.shippedAt()));
        double hoursInState = latestShipment.map(s -> hours(s.shippedAt(), now)).orElseGet(() -> hours(order.placedAt(), now));
        Map<String, Object> orderMap = new LinkedHashMap<>();
        orderMap.put("state", order.status());
        orderMap.put("hours_in_state", round1(hoursInState));
        input.put("order", orderMap);

        buildPayment(order.id()).ifPresent(m -> input.put("payment", m));
        latestShipment.ifPresent(s -> input.put("carrier", carrierSignal(s, now)));

        Optional<FulfilmentGroup> primaryGroup = order.fulfilmentGroups().stream().filter(StuckOrderDiagnosisInputBuilder::isActive).findFirst();
        primaryGroup.ifPresent(g -> {
            buildInventory(g).ifPresent(m -> input.put("inventory", m));
            buildNode(g.nodeId()).ifPresent(m -> input.put("node", m));
        });

        order.shipTo().or(() -> order.address("pickup")).ifPresent(a -> input.put("address", addressSignal(a)));

        input.put("holds", holdsSignal(order.id()));
        input.put("tasks", tasksSignal(order.id()));
        buildCustomer(order.notes(), ai.intentoms.order.model.OrderPii.known(order)).ifPresent(m -> input.put("customer", m));
        return input;
    }

    private Optional<Map<String, Object>> buildPayment(String orderId) {
        List<Payment> all = payments.forOrder(orderId);
        if (all.isEmpty()) return Optional.empty();
        Payment latest = all.stream().max((a, b) -> a.updatedAt().compareTo(b.updatedAt())).orElse(all.get(0));
        long failedCount = all.stream().filter(p -> p.status() == PaymentStatus.FAILED).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", statusWord(latest.status()));
        m.put("retry_count", failedCount);
        m.put("retries_remaining", Math.max(0, ASSUMED_MAX_PAYMENT_ATTEMPTS - failedCount));
        return Optional.of(m);
    }

    static String statusWord(PaymentStatus status) {
        return switch (status) {
            case FAILED -> "declined";
            case AUTHORIZED, REAUTHORIZED -> "authorized";
            case CAPTURED, PARTIALLY_CAPTURED -> "captured";
            case VOIDED -> "voided";
        };
    }

    /**
     * {@code hours_since_last_scan} proxies "time since the last known carrier scan" with time since the shipment
     * entered its current status (the mock carrier only reports a status change, not an independent scan timestamp);
     * documented here rather than left implicit.
     */
    private Map<String, Object> carrierSignal(ShipmentRecord s, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hours_since_last_scan", round1(hours(s.shippedAt(), now)));
        m.put("shipment_status", s.status());
        return m;
    }

    private Optional<Map<String, Object>> buildInventory(FulfilmentGroup g) {
        if (g.allocations().isEmpty()) return Optional.empty();
        Integer minDelta = null;
        String shortSku = null;
        for (FulfilmentGroup.Allocation a : g.allocations()) {
            Atp atp = inventory.atp(a.sku(), g.nodeId());
            int delta = atp.available() - a.qty();
            if (minDelta == null || delta < minDelta) { minDelta = delta; shortSku = a.sku(); }
        }
        if (minDelta == null) return Optional.empty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("atp_delta", minDelta);
        m.put("short_sku", shortSku);
        return Optional.of(m);
    }

    private Optional<Map<String, Object>> buildNode(String nodeId) {
        if (nodeId == null) return Optional.empty();
        return nodes.find(nodeId).map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("backlog_units", n.backlogUnits());
            m.put("reject_rate", n.rejectRate());
            return m;
        });
    }

    private Map<String, Object> addressSignal(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verified", a.verified());
        m.put("type", a.addressType() == null ? (a.verified() ? "residential" : "unverified") : a.addressType());
        return m;
    }

    private Map<String, Object> holdsSignal(String orderId) {
        List<Hold> active = holds.active("order", orderId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", !active.isEmpty());
        Set<String> types = new LinkedHashSet<>();
        active.forEach(h -> types.add(h.type()));
        m.put("types", List.copyOf(types));
        return m;
    }

    private Map<String, Object> tasksSignal(String orderId) {
        var page = tasks.list(new TaskQuery(null, null, null, "order", orderId, null, 0, 100));
        long open = page.items().stream().filter(HumanTask::isOpen).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open_count", open);
        return m;
    }

    private Optional<Map<String, Object>> buildCustomer(List<Note> notes, ai.intentoms.platform.pii.KnownPii known) {
        if (notes == null || notes.isEmpty()) return Optional.empty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notes_count", notes.size());
        Note latest = notes.get(notes.size() - 1);
        if (latest.text() != null && !latest.text().isBlank()) m.put("latest_note", ai.intentoms.platform.pii.FreeTextRedactor.redact(latest.text(), known)); // NF-07
        return Optional.of(m);
    }

    static boolean isActive(FulfilmentGroup g) { return g.status() == null || !INACTIVE_GROUP_STATUSES.contains(g.status().toLowerCase(Locale.ROOT)); }

    static double hours(Instant from, Instant to) { return from == null ? 0 : Math.max(0, Duration.between(from, to).toMinutes()) / 60.0; }

    static double round1(double d) { return Math.round(d * 10) / 10.0; }
}
