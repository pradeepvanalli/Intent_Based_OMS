package ai.intentoms.order.service;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.finance.payment.Refund;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.CatalogService;
import ai.intentoms.order.capture.LineEnricher;
import ai.intentoms.order.config.OrderConfigKeys;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.AgentProvenance;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.model.Charge;
import ai.intentoms.order.model.Customer;
import ai.intentoms.order.model.IntentProfile;
import ai.intentoms.order.model.LedgerEntry;
import ai.intentoms.order.model.MarginSnapshot;
import ai.intentoms.order.model.NextExpectedEvent;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.model.OrderRisk;
import ai.intentoms.order.model.OrderSummary;
import ai.intentoms.order.model.OrderSummaryView;
import ai.intentoms.order.model.OrderTimelineEntry;
import ai.intentoms.order.model.OrderType;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.model.Tender;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.order.validation.AddressVerification;
import ai.intentoms.order.validation.ExtensionSchemaService;
import ai.intentoms.order.validation.ValidationContext;
import ai.intentoms.order.validation.ValidationIssue;
import ai.intentoms.order.validation.ValidationPipeline;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.workflow.model.TimelineEntry;
import ai.intentoms.workflow.model.WorkflowEngine;
import ai.intentoms.workflow.model.WorkflowView;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * {@link OrderService} over the normalised {@link OrderRepository}. Capture (FR-CAP-01/04/05/06/07) is one database
 * transaction: validate → price → insert → initialise state machines → place holds → {@code order.captured} → start
 * workflow; only the {@code Idempotency-Key} claim runs in its own short transaction so concurrent retries serialise.
 * GR-07: nothing in this class calls a model; intent inference runs in the workflow through {@link IntentService}.
 */
@Service
public class DefaultOrderService implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(DefaultOrderService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    static final Set<String> POLICIES = Set.of("ship_complete", "ship_as_available", "earliest_partial", "pickup_consolidated");

    private final OrderRepository repo;
    private final OrderTypeRegistry orderTypes;
    private final CatalogService catalog;
    private final ValidationPipeline pipeline;
    private final ExtensionSchemaService extensionSchemas;
    private final OrderPricing pricing;
    private final StateMachine stateMachine;
    private final StateModelRegistry stateModels;
    private final HoldService holds;
    private final WorkflowEngine workflows;
    private final DecisionService decisions;
    private final PaymentService payments;
    private final EventStore events;
    private final TenantConfigStore config;
    private final Tokenizer tokenizer;
    private final AuthorizationService authz;
    private final IdempotentRequests idempotent;
    private final Metrics metrics;
    private final TimeSource time;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final ShipmentProvider shipments;
    private final ReturnSummaryProvider returns;
    private final OrderRiskProvider risk;
    private final CommunicationProvider communications;

    public DefaultOrderService(OrderRepository repo, OrderTypeRegistry orderTypes, CatalogService catalog, ValidationPipeline pipeline, ExtensionSchemaService extensionSchemas,
                               OrderPricing pricing, StateMachine stateMachine, StateModelRegistry stateModels, HoldService holds, WorkflowEngine workflows, DecisionService decisions,
                               PaymentService payments, EventStore events, TenantConfigStore config, Tokenizer tokenizer, AuthorizationService authz, IdempotentRequests idempotent,
                               Metrics metrics, TimeSource time, TransactionTemplate tx, JdbcClient jdbc, ShipmentProvider shipments, ReturnSummaryProvider returns,
                               OrderRiskProvider risk, CommunicationProvider communications) {
        this.repo = repo;
        this.orderTypes = orderTypes;
        this.catalog = catalog;
        this.pipeline = pipeline;
        this.extensionSchemas = extensionSchemas;
        this.pricing = pricing;
        this.stateMachine = stateMachine;
        this.stateModels = stateModels;
        this.holds = holds;
        this.workflows = workflows;
        this.decisions = decisions;
        this.payments = payments;
        this.events = events;
        this.config = config;
        this.tokenizer = tokenizer;
        this.authz = authz;
        this.idempotent = idempotent;
        this.metrics = metrics;
        this.time = time;
        this.tx = tx;
        this.jdbc = jdbc;
        this.shipments = shipments;
        this.returns = returns;
        this.risk = risk;
        this.communications = communications;
    }

    // ---- capture ------------------------------------------------------------------------------------------------

    @Override
    public Order capture(CaptureRequest req, String idempotencyKey) { return captureDetailed(req, idempotencyKey).order(); }

    @Override
    public CaptureResult captureDetailed(CaptureRequest req, String idempotencyKey) {
        if (req == null) throw new ValidationException("request body is required");
        return metrics.time("oms.order.capture", () -> {
            if (req.channel() != null && req.channelOrderId() != null) {
                Optional<Order> existing = repo.byChannelRef(req.channel(), req.channelOrderId());
                if (existing.isPresent()) {
                    metrics.count("oms.order.capture.replay", "by", "channel_ref");
                    return new CaptureResult(existing.get(), true);
                }
            }
            try {
                IdempotentRequests.Outcome<Map<String, Object>> out = idempotent.executeDetailed("orders.capture", idempotencyKey, req, MAP,
                    () -> Map.of("orderId", tx.execute(s -> doCapture(req)).id()));
                String orderId = String.valueOf(out.value().get("orderId"));
                if (out.replayed()) {
                    metrics.count("oms.order.capture.replay", "by", "idempotency_key");
                    return new CaptureResult(repo.get(orderId), true);
                }
                return new CaptureResult(repo.get(orderId), false);
            } catch (ConflictException e) {
                // Lost a race with a concurrent capture of the same channel reference: replay semantics (FR-CAP-04).
                if (e.details().containsKey("channelOrderId") && req.channel() != null && req.channelOrderId() != null) {
                    Optional<Order> existing = repo.byChannelRef(req.channel(), req.channelOrderId());
                    if (existing.isPresent()) return new CaptureResult(existing.get(), true);
                }
                throw e;
            }
        }, "channel", String.valueOf(req.channel()));
    }

    private Order doCapture(CaptureRequest req) {
        Instant now = time.now();
        Actor actor = Actors.current(authz);
        OrderType type = orderTypes.get(req.effectiveOrderType());
        String currency = req.effectiveCurrency();
        Set<String> skus = new LinkedHashSet<>();
        for (CaptureRequest.LineInput l : req.lines()) if (l.sku() != null) skus.add(l.sku());
        Map<String, CatalogItem> items = catalog.findAll(skus);

        List<OrderLine> lines = new ArrayList<>(req.lines().size());
        for (CaptureRequest.LineInput l : req.lines()) lines.add(LineEnricher.toLine(l, items.get(l.sku()), currency));
        List<Address> addresses = new ArrayList<>();
        for (CaptureRequest.AddressInput a : req.addresses()) addresses.add(toAddress(a, null));
        List<Charge> charges = new ArrayList<>();
        for (CaptureRequest.ChargeInput c : req.charges()) if (c.amount() != null) charges.add(new Charge(c.type() == null ? "fee" : c.type(), c.code(), c.amount(), c.description()));
        Optional<Address> shipTo = addresses.stream().filter(a -> "ship_to".equals(a.type())).findFirst();

        // Validation (FR-CAP-05). The expected total is estimated before validation so the payment and mandate checks see tax.
        Money expectedTotal = estimateTotal(lines, charges, shipTo, currency, req.customerRef());
        ValidationContext ctx = new ValidationContext(type, items, expectedTotal, config.getBoolean(OrderConfigKeys.ALLOW_UNKNOWN_SKU, false),
            config.getDouble(OrderConfigKeys.PRICE_TOLERANCE, 0.25), extensionSchemas.get(ExtensionSchemaService.ORDER).orElse(null));
        List<ValidationIssue> warnings = pipeline.validateOrThrow(req, ctx);

        // Address verification outcomes onto the stored addresses.
        List<Address> verified = new ArrayList<>(addresses.size());
        for (int i = 0; i < addresses.size(); i++) verified.add(toAddress(req.addresses().get(i), ctx.addressResults().get(req.addresses().get(i).type())));
        addresses = verified;
        shipTo = addresses.stream().filter(a -> "ship_to".equals(a.type())).findFirst();

        // Tax, totals (FR-FIN-02) and margin (FR-FIN-04); shipping cost is 0 until sourcing refines it.
        OrderPricing.Priced priced = pricing.price(lines, charges, shipTo, currency, req.customerRef());
        MarginSnapshot margin = pricing.margin(priced.lines(), priced.totals(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);

        IntentProfile intent = explicitIntent(req.intentChoices());
        Customer customer = req.customer() == null ? new Customer(req.customerRef(), null, null, null, null, false, Map.of())
            : new Customer(req.customer().ref() != null ? req.customer().ref() : req.customerRef(), req.customer().name(), req.customer().email(), req.customer().phone(), req.customer().tier(),
                Boolean.TRUE.equals(req.customer().ageVerified()), req.customer().history() == null ? Map.of() : req.customer().history());
        String customerRef = req.customerRef() != null ? req.customerRef() : customer.ref() != null ? customer.ref() : customer.email();
        List<Tender> tenders = new ArrayList<>();
        for (CaptureRequest.TenderInput t : req.tenders()) tenders.add(new Tender(t.type(), t.token(), t.amount(), "pending", null, t.details() == null ? Map.of() : t.details()));
        List<Note> notes = new ArrayList<>();
        for (CaptureRequest.NoteInput n : req.notes()) if (n.text() != null && !n.text().isBlank())
            notes.add(new Note(n.source() == null ? "customer" : n.source(), n.author(), n.text(), now, Boolean.TRUE.equals(n.customerVisible()), null));
        AgentProvenance provenance = provenance(req, priced.totals().grandTotal(), warnings);

        StateModel headerModel = stateModels.latest(type.headerStateModel());
        StateModel lineModel = stateModels.latest(type.lineStateModel());
        String id = Ids.prefixed("ord");
        Order order = new Order(id, req.channel(), req.channelOrderId(), type.name(), customerRef, customer, priced.lines(), addresses, charges, tenders, notes, List.of(), intent, provenance,
            priced.totals(), margin, headerModel.id(), headerModel.version(), headerModel.initial().id(), req.shipMethod(), currency, req.extensions(),
            req.placedAt() == null ? now : req.placedAt(), now, now, 1);
        Order saved = repo.insert(order);

        // State machines (FR-CAP-03): header + one quantity-level entity per line.
        Map<String, Object> headerAttrs = new LinkedHashMap<>();
        headerAttrs.put("channel", saved.channel());
        headerAttrs.put("orderType", saved.orderType());
        headerAttrs.put("agentOrder", saved.isAgentOrder());
        headerAttrs.put("hazmat", saved.hasHazmat());
        headerAttrs.put("grandTotal", saved.totals().grandTotal().amount());
        headerAttrs.put("lineCount", saved.lines().size());
        stateMachine.initialize("order", id, headerModel.id(), headerModel.version(), 1, headerAttrs, actor);
        for (OrderLine l : saved.lines()) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("sku", l.sku());
            attrs.put("hazmat", l.hazmat());
            attrs.put("digital", l.digital());
            attrs.put("orderType", saved.orderType());
            stateMachine.initialize("order_line", id + ":" + l.lineId(), lineModel.id(), lineModel.version(), l.qty(), attrs, actor);
        }
        for (ValidationContext.HoldRequest h : ctx.holds()) holds.place(h.type(), "order", id, h.blocks(), h.reasonCode(), h.note(), null, actor);

        events.append("order", id, new NewEvent("order.captured", capturedPayload(saved, warnings, headerModel, lineModel), actor, null, id, Map.of("channel", saved.channel(), "orderType", saved.orderType())));
        workflows.start(type.workflowType(), id, Map.of("orderId", id, "orderType", type.name(), "channel", saved.channel()));
        metrics.count("oms.order.captured", "channel", saved.channel(), "orderType", saved.orderType(), "agent", String.valueOf(saved.isAgentOrder()));
        log.info("Captured order {} ({}/{}) type={} total={} lines={} warnings={}", id, saved.channel(), saved.channelOrderId(), saved.orderType(), saved.totals().grandTotal(), saved.lines().size(), warnings.size());
        return saved;
    }

    private Money estimateTotal(List<OrderLine> lines, List<Charge> charges, Optional<Address> shipTo, String currency, String customerRef) {
        try {
            return pricing.price(lines, charges, shipTo, currency, customerRef).totals().grandTotal();
        } catch (RuntimeException e) {
            log.debug("Tax estimate before validation failed ({}); using merchandise + charges", e.getMessage());
            Money m = Money.zero(currency);
            for (OrderLine l : lines) m = m.add(l.extendedPrice());
            for (Charge c : charges) if (!"tax".equals(c.type()) && c.amount().currency().equals(currency)) m = m.add(c.amount());
            return m.isNegative() ? Money.zero(currency) : m;
        }
    }

    static Address toAddress(CaptureRequest.AddressInput a, AddressVerification v) {
        String addressType = v != null && v.addressType() != null ? v.addressType() : a.addressType();
        return new Address(a.type(), a.name(), a.line1(), a.line2(), a.city(), a.state(), a.zip(), a.country() == null || a.country().isBlank() ? "US" : a.country().trim().toUpperCase(),
            a.phone(), addressType, v != null && v.verified(), v == null ? null : v.lat(), v == null ? null : v.lon());
    }

    /** Explicit buyer choice ⇒ source explicit, confidence 1.0; otherwise unclear until the workflow runs {@code fulfilment_intent} (FR-CAP-06). */
    static IntentProfile explicitIntent(List<String> choices) {
        if (choices == null || choices.isEmpty()) return IntentProfile.unclear(choices);
        for (String c : choices) if (c != null && POLICIES.contains(c.trim().toLowerCase())) {
            return new IntentProfile(c.trim().toLowerCase(), 1.0, "explicit", choices, null, null, choices.stream().anyMatch(x -> x != null && x.toLowerCase().contains("gift")) ? Boolean.TRUE : null);
        }
        return IntentProfile.unclear(choices);
    }

    private static AgentProvenance provenance(CaptureRequest req, Money total, List<ValidationIssue> warnings) {
        CaptureRequest.ProvenanceInput p = req.provenance();
        if (p == null) return null;
        boolean beyond = warnings.stream().anyMatch(w -> "beyond_mandate".equals(w.code()));
        Boolean within = p.spendLimit() == null ? null : !beyond && p.spendLimit().currency().equals(total.currency()) && p.spendLimit().compareTo(total) >= 0 ? Boolean.TRUE : beyond ? Boolean.FALSE : null;
        return new AgentProvenance(p.agentId(), p.protocol() == null ? null : p.protocol().toLowerCase(), p.mandateId(), p.spendLimit(), p.instructionText(), p.signature(), p.buyerRef(), within, null,
            p.raw() == null ? Map.of() : p.raw());
    }

    private Map<String, Object> capturedPayload(Order o, List<ValidationIssue> warnings, StateModel header, StateModel line) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", o.id());
        p.put("channel", o.channel());
        p.put("channelOrderId", o.channelOrderId());
        p.put("orderType", o.orderType());
        p.put("customerRef", o.customerRef());
        if (o.customer() != null && o.customer().email() != null) p.put("customerEmailToken", tokenizer.email(o.customer().email()));
        if (o.customer() != null && o.customer().tier() != null) p.put("customerTier", o.customer().tier());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderLine l : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineId", l.lineId());
            lm.put("sku", l.sku());
            lm.put("qty", l.qty());
            lm.put("unitPrice", Map.of("amount", l.unitPrice().amount(), "currency", l.unitPrice().currency()));
            lm.put("tax", l.tax() == null ? null : l.tax().amount());
            lm.put("hazmat", l.hazmat());
            lines.add(lm);
        }
        p.put("lines", lines);
        p.put("totals", Json.toMap(o.totals()));
        p.put("margin", Map.of("marginRatio", o.margin().marginRatio(), "floor", o.margin().floor(), "belowFloor", o.margin().belowFloor()));
        p.put("intent", Json.toMap(o.intent()));
        p.put("shipMethod", o.shipMethod());
        o.shipTo().ifPresent(a -> p.put("shipTo", Map.of("state", String.valueOf(a.state()), "zip", String.valueOf(a.zip()), "country", String.valueOf(a.country()), "addressType", String.valueOf(a.addressType()), "verified", a.verified())));
        p.put("agentOrder", o.isAgentOrder());
        if (o.provenance() != null) {
            p.put("agentId", o.provenance().agentId());
            p.put("protocol", o.provenance().protocol());
            p.put("mandateId", o.provenance().mandateId());
        }
        p.put("stateModels", Map.of("header", header.id(), "headerVersion", header.version(), "line", line.id(), "lineVersion", line.version()));
        p.put("validationWarnings", warnings.stream().map(Json::toMap).toList());
        p.put("placedAt", o.placedAt().toString());
        return p;
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    @Override
    public Order get(String orderId) { return repo.get(orderId); }

    @Override
    public Optional<Order> byChannelRef(String channel, String channelOrderId) { return repo.byChannelRef(channel, channelOrderId); }

    @Override
    public Page<OrderSummary> search(OrderQuery q) { return repo.search(q); }

    @Override
    public Page<OrderSummaryView> searchView(OrderQuery q, Boolean atRisk) {
        Page<OrderSummary> page = repo.search(q);
        List<String> ids = page.items().stream().map(OrderSummary::id).toList();
        Map<String, Map<String, Object>> extras = listExtras(ids);
        Map<String, OrderRisk> risks = ids.isEmpty() ? Map.of() : risk.riskFor(ids);
        List<OrderSummaryView> items = new ArrayList<>();
        for (OrderSummary s : page.items()) {
            Map<String, Object> x = extras.getOrDefault(s.id(), Map.of());
            OrderRisk r = risks.get(s.id());
            if (atRisk != null && atRisk != (r != null && r.atRisk())) continue;
            @SuppressWarnings("unchecked") List<String> nodeIds = (List<String>) x.getOrDefault("nodeIds", List.of());
            items.add(OrderSummaryView.of(s, (String) x.get("agentId"), nodeIds, Boolean.TRUE.equals(x.get("held")), r, (LocalDate) x.get("promiseDate")));
        }
        long total = atRisk == null ? page.total() : items.size();
        return Page.of(items, total, page.offset(), page.limit());
    }

    /** One query per page for the list extras: agent id, fulfilment-group node ids, earliest promise date, active hold. */
    private Map<String, Map<String, Object>> listExtras(List<String> ids) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        String t = TenantContext.require();
        var rows = jdbc.sql("""
            select o.id, o.provenance->>'agentId' as agent_id,
                   (select string_agg(distinct g.node_id, ',') from fulfilment_group g where g.tenant_id = o.tenant_id and g.order_id = o.id and g.node_id is not null and g.status not in ('rejected', 'cancelled')) as node_ids,
                   (select min(g.promise_date) from fulfilment_group g where g.tenant_id = o.tenant_id and g.order_id = o.id and g.status not in ('rejected', 'cancelled')) as promise_date,
                   exists (select 1 from hold h where h.tenant_id = o.tenant_id and h.scope_type = 'order' and h.scope_id = o.id and h.resolved_at is null and (h.expires_at is null or h.expires_at > now())) as held
            from orders o where o.tenant_id = :t and o.id in (:ids)
            """).param("t", t).param("ids", ids).query().listOfRows();
        for (var r : rows) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("agentId", r.get("agent_id"));
            String nodes = (String) r.get("node_ids");
            x.put("nodeIds", nodes == null || nodes.isBlank() ? List.of() : List.of(nodes.split(",")));
            x.put("promiseDate", r.get("promise_date") == null ? null : ((java.sql.Date) r.get("promise_date")).toLocalDate());
            x.put("held", Boolean.TRUE.equals(r.get("held")));
            out.put((String) r.get("id"), x);
        }
        return out;
    }

    @Override
    public OrderView view(String orderId) {
        return metrics.time("oms.order.view", () -> {
            Order o = repo.get(orderId);
            EntityStatus header = statusOrNull("order", o.id());
            List<String> lineIds = o.lines().stream().map(l -> o.id() + ":" + l.lineId()).toList();
            List<EntityStatus> lineStatuses = lineIds.isEmpty() ? List.of() : stateMachine.statuses("order_line", lineIds);
            List<Hold> activeHolds = new ArrayList<>(holds.active("order", o.id()));
            for (String lid : lineIds) activeHolds.addAll(holds.active("order_line", lid));
            List<Payment> pays = payments.forOrder(o.id());
            List<Refund> refunds = payments.refundsForOrder(o.id());
            List<DecisionRecord> records = decisions.forSubject("order", o.id());
            WorkflowView wf = workflowFor(o).orElse(null);
            List<TimelineEntry> wfTimeline = wf == null ? List.of() : workflows.timeline(wf.id());
            List<Event> ledger = events.load("order", o.id());
            List<OrderTimelineEntry> timeline = mergeTimeline(ledger, wfTimeline, records);
            return new OrderView(o, header, lineStatuses, o.fulfilmentGroups(), activeHolds, shipments.shipmentsFor(o.id()), returns.returnsFor(o.id()), pays, refunds,
                records.stream().map(DecisionRecord::id).toList(), records, wf, timeline, ledger.stream().map(LedgerEntry::of).toList(), communications.communicationsFor(o.id()),
                risk.riskFor(o.id()).orElse(null), nextExpected(wfTimeline));
        });
    }

    @Override
    public List<OrderTimelineEntry> timeline(String orderId) {
        Order o = repo.get(orderId);
        WorkflowView wf = workflowFor(o).orElse(null);
        return mergeTimeline(events.load("order", o.id()), wf == null ? List.of() : workflows.timeline(wf.id()), decisions.forSubject("order", o.id()));
    }

    private EntityStatus statusOrNull(String type, String id) {
        try { return stateMachine.status(type, id); } catch (NotFoundException e) { return null; }
    }

    private Optional<WorkflowView> workflowFor(Order o) {
        String type;
        try { type = orderTypes.get(o.orderType()).workflowType(); } catch (NotFoundException e) { type = "order." + o.orderType(); }
        return workflows.find(type, o.id());
    }

    static List<OrderTimelineEntry> mergeTimeline(List<Event> ledger, List<TimelineEntry> workflow, List<DecisionRecord> records) {
        List<OrderTimelineEntry> out = new ArrayList<>();
        for (Event e : ledger) out.add(new OrderTimelineEntry(e.occurredAt(), "event", e.type(), null, e.payload(), "ledger"));
        for (TimelineEntry t : workflow) out.add(new OrderTimelineEntry(t.at(), t.kind(), t.name(), t.status(), t.details(), "workflow"));
        for (DecisionRecord r : records) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("recordId", r.id());
            d.put("value", r.effectiveValue());
            d.put("confidence", r.confidence());
            d.put("band", r.band() == null ? null : r.band().name());
            d.put("layer", r.layer() == null ? null : r.layer().name());
            d.put("version", r.version());
            d.put("provider", r.provider());
            d.put("overridden", r.overriddenAt() != null);
            out.add(new OrderTimelineEntry(r.createdAt(), "decision", r.decision(), r.action(), d, "decision"));
        }
        out.sort(Comparator.comparing(OrderTimelineEntry::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** The synthesized "expected" workflow entry (signal, task or timer) becomes the order's next expected event. */
    static NextExpectedEvent nextExpected(List<TimelineEntry> wfTimeline) {
        Instant by = null;
        String name = null;
        for (TimelineEntry t : wfTimeline) {
            if ("timer".equals(t.kind()) && "pending".equals(t.status())) {
                Object fireAt = t.details() == null ? null : t.details().get("fireAt");
                if (fireAt != null) by = parseInstant(fireAt);
                if (name == null) name = "timer:" + t.name();
            }
            if ("expected".equals(t.status()) && ("signal".equals(t.kind()) || "task".equals(t.kind()))) name = ("task".equals(t.kind()) ? "task:" : "") + t.name();
        }
        return name == null ? null : new NextExpectedEvent(name, by);
    }

    private static Instant parseInstant(Object v) {
        if (v instanceof Instant i) return i;
        try { return Instant.parse(String.valueOf(v)); } catch (RuntimeException e) { return null; }
    }

    // ---- writes -------------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public Order update(String orderId, UnaryOperator<Order> fn, NewEvent event) {
        Order current = repo.get(orderId);
        Order next = fn.apply(current);
        if (next == null) throw new IllegalArgumentException("update function returned null for order " + orderId);
        Order saved = repo.update(next);
        if (event != null) events.append("order", orderId, event.correlationId() == null ? event.withCorrelation(orderId, event.causationId()) : event);
        return saved;
    }

    @Override
    @Transactional
    public Order addNote(String orderId, Note note) {
        if (note == null || note.text() == null || note.text().isBlank()) throw new ValidationException("note text is required");
        Actor actor = Actors.current(authz);
        Note stored = new Note(note.source() == null ? "cs" : note.source(), note.author() == null ? actor.id() : note.author(), note.text(), note.at() == null ? time.now() : note.at(), note.customerVisible(), note.injectionRisk());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", stored.source());
        payload.put("author", stored.author());
        payload.put("customerVisible", stored.customerVisible());
        payload.put("text", stored.text());
        return update(orderId, o -> { List<Note> notes = new ArrayList<>(o.notes() == null ? List.of() : o.notes()); notes.add(stored); return o.withNotes(notes); },
            new NewEvent("order.note_added", payload, actor));
    }

    @Override
    @Transactional
    public Order recomputeMargin(String orderId, BigDecimal shippingCost, BigDecimal labourCost, BigDecimal packagingCost) {
        Actor actor = Actors.current(authz);
        Order updated = update(orderId, o -> o.withMargin(pricing.margin(o.lines(), o.totals(), shippingCost, labourCost, packagingCost, o.margin())), null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("marginRatio", updated.margin().marginRatio());
        p.put("contributionMargin", updated.margin().contributionMargin());
        p.put("floor", updated.margin().floor());
        p.put("belowFloor", updated.margin().belowFloor());
        p.put("approvalPresent", updated.margin().approvalPresent());
        p.put("shippingCost", shippingCost);
        p.put("labourCost", labourCost);
        p.put("packagingCost", packagingCost);
        events.append("order", orderId, new NewEvent("order.margin_recomputed", p, actor, null, orderId, Map.of()));
        return updated;
    }

    @Override
    @Transactional
    public Order markBelowFloorApproval(String orderId, String taskId) {
        Actor actor = Actors.current(authz);
        return update(orderId, o -> {
            MarginSnapshot m = o.margin();
            MarginSnapshot approved = new MarginSnapshot(m == null ? null : m.revenue(), m == null ? null : m.contributionMargin(), m == null ? 0 : m.marginRatio(),
                m == null ? config.getDouble(OrderConfigKeys.MARGIN_FLOOR, 0.0) : m.floor(), m != null && m.belowFloor(), true, taskId, time.now());
            return o.withMargin(approved);
        }, new NewEvent("order.margin_approved", Map.of("taskId", String.valueOf(taskId)), actor));
    }

}
