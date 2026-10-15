package ai.intentoms.order.modification;

import ai.intentoms.finance.compliance.ComplianceCustomer;
import ai.intentoms.finance.compliance.ComplianceDestination;
import ai.intentoms.finance.compliance.ComplianceInput;
import ai.intentoms.finance.compliance.ComplianceLine;
import ai.intentoms.finance.compliance.ComplianceService;
import ai.intentoms.finance.compliance.ComplianceViolation;
import ai.intentoms.finance.model.Money;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.CatalogService;
import ai.intentoms.order.config.OrderConfigKeys;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.model.Charge;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.OrderType;
import ai.intentoms.order.service.OrderPricing;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.service.OrderTypeRegistry;
import ai.intentoms.order.validation.AddressVerification;
import ai.intentoms.order.validation.AddressVerifier;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.TransitionDef;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link OrderModificationService} (FR-CS-02). Feasibility is decided by {@link ModificationRules} over live facts: each
 * line's quantity buckets and the states its model lets {@code api.cancel_line} drain right now ({@link StateMachine#available}),
 * network ATP for increases and swaps, catalogue prices, and the tenant's carrier-intercept policy. {@link #apply} runs
 * in one transaction: transitions first (the projector updates the status columns), then an optimistic order update with
 * re-pricing, then the ledger events and the {@code order.modified} workflow signal keyed by the event id.
 */
@Service
public class DefaultOrderModificationService implements OrderModificationService {
    private static final Logger log = LoggerFactory.getLogger(DefaultOrderModificationService.class);
    public static final String SIGNAL = "order.modified";
    static final String CANCEL_LINE = "api.cancel_line";
    static final String CANCEL_ORDER = "api.cancel_order";

    private final OrderService orders;
    private final OrderTypeRegistry orderTypes;
    private final StateMachine stateMachine;
    private final StateModelRegistry stateModels;
    private final InventoryService inventory;
    private final CatalogService catalog;
    private final OrderPricing pricing;
    private final AddressVerifier addresses;
    private final ComplianceService compliance;
    private final WorkflowEngine workflows;
    private final EventStore events;
    private final TenantConfigStore config;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public DefaultOrderModificationService(OrderService orders, OrderTypeRegistry orderTypes, StateMachine stateMachine, StateModelRegistry stateModels, InventoryService inventory,
                                           CatalogService catalog, OrderPricing pricing, AddressVerifier addresses, ComplianceService compliance, WorkflowEngine workflows,
                                           EventStore events, TenantConfigStore config, AuthorizationService authz, Metrics metrics) {
        this.orders = orders;
        this.orderTypes = orderTypes;
        this.stateMachine = stateMachine;
        this.stateModels = stateModels;
        this.inventory = inventory;
        this.catalog = catalog;
        this.pricing = pricing;
        this.addresses = addresses;
        this.compliance = compliance;
        this.workflows = workflows;
        this.events = events;
        this.config = config;
        this.authz = authz;
        this.metrics = metrics;
    }

    @Override
    public Feasibility check(String orderId, Modification m) {
        if (m == null) throw new ValidationException("modification is required");
        Order o = orders.get(orderId);
        Feasibility f = ModificationRules.check(o, m, facts(o, m));
        if (f.allowed() && m instanceof Modification.ChangeShipMethod c) {
            List<ComplianceViolation> blocks = complianceBlocks(o, o.lines(), c.shipMethod());
            if (!blocks.isEmpty()) f = Feasibility.denied(blocks.get(0).reasonCode() + ": " + blocks.get(0).message(), f.affectedLines());
        }
        if (f.allowed() && m instanceof Modification.ChangeAddress c) {
            List<ComplianceViolation> blocks = complianceBlocks(o.withAddresses(replaceShipTo(o.addresses(), c.address())), o.lines(), o.shipMethod());
            if (!blocks.isEmpty()) f = Feasibility.denied(blocks.get(0).reasonCode() + ": " + blocks.get(0).message(), f.affectedLines());
        }
        metrics.count("oms.order.modification.check", "type", m.type(), "allowed", String.valueOf(f.allowed()));
        return f;
    }

    /** Live facts for the rules: line buckets + cancellable states from the model, ATP, catalogue prices, tenant policy, address verification. */
    ModificationRules.Facts facts(Order o, Modification m) {
        Map<String, ModificationRules.LineFacts> lines = new LinkedHashMap<>();
        List<String> ids = o.lines().stream().map(l -> o.id() + ":" + l.lineId()).toList();
        Map<String, EntityStatus> statuses = new LinkedHashMap<>();
        if (!ids.isEmpty()) for (EntityStatus s : stateMachine.statuses("order_line", ids)) statuses.put(s.entityId(), s);
        Map<String, Object> vars = Map.of("ctx", Map.of("modification", m.type()));
        for (OrderLine l : o.lines()) {
            EntityStatus s = statuses.get(o.id() + ":" + l.lineId());
            Map<String, Integer> qty = s == null ? Map.of(l.status() == null ? "created" : l.status(), l.qty()) : s.qtyMap();
            Set<String> cancellable = new LinkedHashSet<>();
            if (s != null) {
                for (TransitionDef t : stateMachine.available("order_line", s.entityId(), vars)) if (CANCEL_LINE.equals(t.trigger())) cancellable.addAll(t.from());
            }
            lines.put(l.lineId(), new ModificationRules.LineFacts(l.lineId(), qty, cancellable));
        }
        String headerState = o.status();
        try { headerState = stateMachine.status("order", o.id()).state(); } catch (NotFoundException ignored) { }

        Map<String, Integer> atp = Map.of();
        Map<String, Money> prices = Map.of();
        if (m instanceof Modification.ChangeQty c) {
            Set<String> skus = o.line(c.lineId()).map(l -> Set.of(l.sku())).orElse(Set.of());
            atp = skus.isEmpty() ? Map.of() : inventory.networkAtp(skus);
        } else if (m instanceof Modification.ChangeItem c && c.newSku() != null) {
            atp = inventory.networkAtp(Set.of(c.newSku()));
            Optional<CatalogItem> item = catalog.find(c.newSku());
            if (item.isPresent() && item.get().price(o.currency()) != null) prices = Map.of(c.newSku(), item.get().price(o.currency()));
        }
        boolean deliverable = true;
        if (m instanceof Modification.ChangeAddress c && c.address() != null) deliverable = verify(c.address()).deliverable();
        Money fee = Money.of(config.getDecimal(OrderConfigKeys.CARRIER_INTERCEPT_FEE_USD, new BigDecimal("15.00")), o.currency());
        return new ModificationRules.Facts(lines, headerState, atp, config.getBoolean(OrderConfigKeys.CARRIER_INTERCEPT_ENABLED, false), fee, prices, deliverable);
    }

    @Override
    @Transactional
    public Order apply(String orderId, Modification m, String reason, Actor actor) {
        if (m == null) throw new ValidationException("modification is required");
        boolean cancellation = m instanceof Modification.CancelLine || m instanceof Modification.CancelOrder;
        authz.require(cancellation ? Permissions.ORDER_CANCEL : Permissions.ORDER_MODIFY);
        Actor who = actor != null ? actor : authz.currentPrincipal().map(p -> Actor.parse(p.actorRef())).orElse(Actor.system("order"));
        Order before = orders.get(orderId);
        Feasibility f = check(orderId, m);
        if (!f.allowed()) throw new ConflictException("modification_not_allowed: " + String.join("; ", f.reasons()), Map.of("type", m.type(), "reasons", f.reasons(), "affectedLines", f.affectedLines()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", m.type());
        payload.put("reason", reason);
        payload.put("costDelta", f.costDelta() == null ? null : Map.of("amount", f.costDelta().amount(), "currency", f.costDelta().currency()));
        payload.put("affectedLines", f.affectedLines());
        payload.put("requiresApproval", f.requiresApproval());
        List<NewEvent> extra = new ArrayList<>();

        Order after = switch (m) {
            case Modification.CancelLine c -> {
                int cancelled = fireCancel(before, c.lineId(), null, reason, who);
                payload.put("lineId", c.lineId());
                payload.put("qty", cancelled);
                extra.add(new NewEvent("line.cancelled", Map.of("lineId", c.lineId(), "qty", cancelled, "reason", String.valueOf(reason)), who, null, orderId, Map.of()));
                yield orders.update(orderId, o -> pricing.reprice(o.withLines(markCancelled(o, c.lineId())), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null);
            }
            case Modification.CancelOrder c -> {
                Map<String, Integer> cancelledQty = new LinkedHashMap<>();
                for (String lineId : f.affectedLines()) cancelledQty.put(lineId, fireCancel(before, lineId, null, reason, who));
                TransitionResult header = stateMachine.fire(new TransitionRequest("order", orderId, CANCEL_ORDER, null, null, Map.of("ctx", Map.of("reason", String.valueOf(reason))), who, orderId, null));
                log.debug("api.cancel_order on {} applied={} ({})", orderId, header.applied(), header.blockedBy());
                payload.put("lines", cancelledQty);
                extra.add(new NewEvent("order.cancelled", Map.of("reason", String.valueOf(reason), "lines", cancelledQty, "refund", payload.get("costDelta") == null ? Map.of() : payload.get("costDelta")), who, null, orderId, Map.of()));
                yield orders.update(orderId, o -> {
                    List<OrderLine> lines = new ArrayList<>();
                    for (OrderLine l : o.lines()) lines.add(cancelledQty.containsKey(l.lineId()) ? l.withStatus("cancelled") : l);
                    return pricing.reprice(o.withLines(lines), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                }, null);
            }
            case Modification.ChangeQty c -> {
                OrderLine line = before.line(c.lineId()).orElseThrow();
                payload.put("lineId", c.lineId());
                payload.put("fromQty", line.qty());
                payload.put("qty", c.qty());
                if (c.qty() < line.qty()) {
                    int decrease = line.qty() - c.qty();
                    int cancelled = fireCancel(before, c.lineId(), decrease, reason, who);
                    extra.add(new NewEvent("line.cancelled", Map.of("lineId", c.lineId(), "qty", cancelled, "reason", String.valueOf(reason)), who, null, orderId, Map.of()));
                    yield orders.update(orderId, o -> pricing.reprice(o.withLines(adjustQty(o, c.lineId(), c.qty(), cancelled)), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null);
                }
                int increase = c.qty() - line.qty();
                String newLineId = supplementaryLineId(before, c.lineId());
                payload.put("supplementaryLineId", newLineId);
                Order updated = orders.update(orderId, o -> {
                    List<OrderLine> lines = new ArrayList<>(o.lines());
                    Map<String, Object> attrs = new LinkedHashMap<>(line.attributes() == null ? Map.of() : line.attributes());
                    attrs.put("supplements_line", c.lineId());
                    lines.add(new OrderLine(newLineId, line.sku(), line.description(), increase, line.unitPrice(), Money.zero(o.currency()), Money.zero(o.currency()), null, null, attrs));
                    return pricing.reprice(o.withLines(lines), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                }, null);
                OrderType type = orderTypes.get(updated.orderType());
                StateModel lineModel = stateModels.latest(type.lineStateModel());
                stateMachine.initialize("order_line", orderId + ":" + newLineId, lineModel.id(), lineModel.version(), increase,
                    Map.of("sku", line.sku(), "hazmat", line.hazmat(), "supplements", c.lineId()), who);
                yield updated;
            }
            case Modification.ChangeAddress c -> {
                AddressVerification v = verify(c.address());
                Address verified = new Address("ship_to", c.address().name(), c.address().line1(), c.address().line2(), c.address().city(), c.address().state(), c.address().zip(),
                    c.address().country() == null ? "US" : c.address().country(), c.address().phone(), v.addressType() != null ? v.addressType() : c.address().addressType(), v.verified(), v.lat(), v.lon());
                payload.put("address", Map.of("state", String.valueOf(verified.state()), "zip", String.valueOf(verified.zip()), "country", String.valueOf(verified.country()), "addressType", String.valueOf(verified.addressType())));
                payload.put("carrierIntercept", Boolean.TRUE.equals(f.carrierIntercept()));
                yield orders.update(orderId, o -> {
                    Order next = o.withAddresses(replaceShipTo(o.addresses(), verified));
                    if (Boolean.TRUE.equals(f.carrierIntercept()) && f.costDelta() != null && f.costDelta().isPositive()) {
                        List<Charge> charges = new ArrayList<>(next.charges() == null ? List.of() : next.charges());
                        charges.add(new Charge("fee", "carrier_intercept", f.costDelta(), "Carrier intercept fee"));
                        next = new Order(next.id(), next.channel(), next.channelOrderId(), next.orderType(), next.customerRef(), next.customer(), next.lines(), next.addresses(), charges, next.tenders(), next.notes(),
                            next.fulfilmentGroups(), next.intent(), next.provenance(), next.totals(), next.margin(), next.stateModelId(), next.stateModelVersion(), next.status(), next.shipMethod(), next.currency(),
                            next.extensions(), next.placedAt(), next.capturedAt(), next.updatedAt(), next.version());
                    }
                    return pricing.reprice(next, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                }, null);
            }
            case Modification.ChangeShipMethod c -> {
                payload.put("fromShipMethod", before.shipMethod());
                payload.put("shipMethod", c.shipMethod());
                yield orders.update(orderId, o -> pricing.reprice(o.withShipMethod(c.shipMethod()), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null);
            }
            case Modification.ChangeItem c -> {
                CatalogItem item = catalog.get(c.newSku());
                OrderLine line = before.line(c.lineId()).orElseThrow();
                payload.put("lineId", c.lineId());
                payload.put("fromSku", line.sku());
                payload.put("sku", c.newSku());
                yield orders.update(orderId, o -> {
                    List<OrderLine> lines = new ArrayList<>();
                    for (OrderLine l : o.lines()) {
                        if (!l.lineId().equals(c.lineId())) { lines.add(l); continue; }
                        Money price = item.price(o.currency()) == null ? l.unitPrice() : item.price(o.currency());
                        lines.add(new OrderLine(l.lineId(), item.sku(), item.name(), l.qty(), price, Money.zero(o.currency()), Money.zero(o.currency()), l.fulfilmentGroupId(), l.status(), item.lineAttributes()));
                    }
                    return pricing.reprice(o.withLines(lines), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                }, null);
            }
        };

        payload.put("totals", ai.intentoms.platform.json.Json.toMap(after.totals()));
        payload.put("marginRatio", after.margin() == null ? null : after.margin().marginRatio());
        Event modified = events.append("order", orderId, new NewEvent("order.modified", payload, who, null, orderId, Map.of("type", m.type())));
        for (NewEvent e : extra) events.append("order", orderId, e);
        signalWorkflow(after, m, payload, modified.id());
        metrics.count("oms.order.modification.applied", "type", m.type());
        log.info("Applied {} to order {} by {} (reason: {})", m.type(), orderId, who.ref(), reason);
        return after;
    }

    /** Fires {@code api.cancel_line} for {@code qty} units (null = every cancellable unit); returns the quantity moved. */
    private int fireCancel(Order o, String lineId, Integer qty, String reason, Actor actor) {
        String entityId = o.id() + ":" + lineId;
        TransitionResult r = stateMachine.fire(new TransitionRequest("order_line", entityId, CANCEL_LINE, qty, null, Map.of("ctx", Map.of("reason", String.valueOf(reason))), actor, o.id(), null));
        if (!r.applied()) throw new ConflictException("cancel_blocked: line " + lineId + " could not be cancelled", Map.of("lineId", lineId, "blockedBy", r.blockedBy()));
        return r.qty();
    }

    private static List<OrderLine> markCancelled(Order o, String lineId) {
        List<OrderLine> lines = new ArrayList<>();
        for (OrderLine l : o.lines()) lines.add(l.lineId().equals(lineId) ? l.withStatus("cancelled") : l);
        return lines;
    }

    private static List<OrderLine> adjustQty(Order o, String lineId, int newQty, int cancelled) {
        List<OrderLine> lines = new ArrayList<>();
        for (OrderLine l : o.lines()) {
            if (!l.lineId().equals(lineId)) { lines.add(l); continue; }
            Map<String, Object> attrs = new LinkedHashMap<>(l.attributes() == null ? Map.of() : l.attributes());
            int already = attrs.get("cancelled_qty") instanceof Number n ? n.intValue() : 0;
            attrs.put("cancelled_qty", already + cancelled);
            Money discount = l.discount() == null ? Money.zero(l.unitPrice().currency()) : l.discount().multiply(BigDecimal.valueOf(newQty).divide(BigDecimal.valueOf(l.qty()), 6, java.math.RoundingMode.HALF_EVEN));
            lines.add(new OrderLine(l.lineId(), l.sku(), l.description(), newQty, l.unitPrice(), discount, l.tax(), l.fulfilmentGroupId(), l.status(), attrs));
        }
        return lines;
    }

    static String supplementaryLineId(Order o, String lineId) {
        int n = 1;
        while (true) {
            String candidate = lineId + "-" + n;
            if (o.line(candidate).isEmpty()) return candidate;
            n++;
        }
    }

    static List<Address> replaceShipTo(List<Address> current, Address shipTo) {
        List<Address> out = new ArrayList<>();
        if (current != null) for (Address a : current) if (!"ship_to".equals(a.type())) out.add(a);
        Address typed = "ship_to".equals(shipTo.type()) ? shipTo : new Address("ship_to", shipTo.name(), shipTo.line1(), shipTo.line2(), shipTo.city(), shipTo.state(), shipTo.zip(), shipTo.country(), shipTo.phone(), shipTo.addressType(), shipTo.verified(), shipTo.lat(), shipTo.lon());
        out.add(typed);
        return out;
    }

    private AddressVerification verify(Address a) {
        return addresses.verify(new CaptureRequest.AddressInput("ship_to", a.name(), a.line1(), a.line2(), a.city(), a.state(), a.zip(), a.country(), a.phone(), a.addressType()));
    }

    private List<ComplianceViolation> complianceBlocks(Order o, List<OrderLine> lines, String shipMethod) {
        List<ComplianceLine> cl = new ArrayList<>();
        for (OrderLine l : lines) if (!"cancelled".equals(l.status())) cl.add(new ComplianceLine(l.lineId(), l.sku(), l.qty(), l.attributes()));
        Address ship = o.shipTo().orElse(null);
        ComplianceDestination dest = ship == null ? new ComplianceDestination(null, null, null, null) : new ComplianceDestination(ship.state(), ship.zip(), ship.country(), ship.addressType());
        ComplianceCustomer customer = o.customer() == null ? ComplianceCustomer.unverified() : new ComplianceCustomer(o.customer().ageVerified(), null);
        return compliance.check(new ComplianceInput(cl, dest, customer, shipMethod)).stream().filter(ComplianceViolation::blocks).toList();
    }

    private void signalWorkflow(Order o, Modification m, Map<String, Object> payload, String eventId) {
        String type;
        try { type = orderTypes.get(o.orderType()).workflowType(); } catch (NotFoundException e) { type = "order." + o.orderType(); }
        if (workflows.find(type, o.id()).isEmpty()) {
            log.warn("No workflow {} for order {}; order.modified signal not delivered", type, o.id());
            return;
        }
        Map<String, Object> signal = new LinkedHashMap<>(payload);
        signal.put("orderId", o.id());
        signal.put("eventId", eventId);
        workflows.signal(type, o.id(), SIGNAL, signal, eventId);
    }
}
