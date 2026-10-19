package ai.intentoms.orchestration.cx;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.AppeasementLevelLearner;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CX-04: a complaint contact gets an appeasement recommendation from the T2 {@code appeasement} decision. The credit
 * offered is the learned level for the customer's segment ({@code APPEASEMENT_LEVEL}, through
 * {@code ParameterResolver} family {@code cs.appeasement_level}) or the tenant's {@code cs.appeasement_default_usd}
 * (default 10), and the per-case budget {@code cs.appeasement_budget_usd_per_case} (default 25) is passed as the limit,
 * so the decision's own rule escalates anything above it. Nothing is issued here: the recommendation is recorded as
 * {@code cs.appeasement_recommended} and, being T2, a person approves any credit (the decision escalates to the
 * refunds queue when it is not confident).
 *
 * <p>A contact counts as a complaint when its event is {@code cs.complaint}, or a {@code cs.contact} / {@code cs.wismo}
 * whose payload says {@code complaint: true} or {@code reason: complaint}. A redelivered event is ignored.
 */
@Component
public class ComplaintAppeasementSubscriber implements EventSubscriber {
    public static final String DECISION = "appeasement";
    public static final String EVT_RECOMMENDED = "cs.appeasement_recommended";
    private static final Logger log = LoggerFactory.getLogger(ComplaintAppeasementSubscriber.class);
    private static final Actor ACTOR = Actor.system("appeasement");

    private final OrderRepository orders;
    private final DecisionService decisions;
    private final EventStore events;
    private final TenantConfigStore config;
    private final LearnedFactCache facts;
    private final ParameterResolver parameters;

    public ComplaintAppeasementSubscriber(OrderRepository orders, DecisionService decisions, EventStore events, TenantConfigStore config, LearnedFactCache facts,
                                          ParameterResolver parameters) {
        this.orders = orders;
        this.decisions = decisions;
        this.events = events;
        this.config = config;
        this.facts = facts;
        this.parameters = parameters;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("cs.complaint", "cs.contact", "cs.wismo"); }

    @Override
    public void on(Event e) {
        if (!isComplaint(e)) return;
        var previousTenant = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            String orderId = orderIdOf(e);
            if (orderId == null) return;
            Optional<Order> order = orders.find(orderId);
            if (order.isEmpty()) return;
            if (alreadyHandled(orderId, e.id())) return;
            recommend(order.get(), e);
        } catch (RuntimeException ex) {
            log.warn("Appeasement recommendation failed for event {}: {}", e.id(), ex.toString());
        } finally {
            previousTenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    DecisionResult recommend(Order order, Event e) {
        String segment = AppeasementLevelLearner.segment(order.customer() == null ? null : order.customer().tier());
        double budget = config.getDouble(AppeasementLevelLearner.BUDGET_KEY, 25.0);
        double staticLevel = config.getDouble(AppeasementLevelLearner.STATIC_KEY, 10.0);
        Double learned = level(segment).or(() -> level(AppeasementLevelLearner.segment(null))).orElse(null);
        ParameterResolver.Resolution level = parameters.explain(AppeasementLevelLearner.PARAMETER, staticLevel, learned);
        double credit = Math.min(budget, level.value() == null ? staticLevel : level.value());

        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("case", Map.of("issue", ai.intentoms.platform.pii.FreeTextRedactor.redact(str(p.getOrDefault("issue", p.getOrDefault("reason", "complaint"))), ai.intentoms.order.model.OrderPii.known(order)), "severity", str(p.getOrDefault("severity", "medium")),
            "customer_sentiment", str(p.getOrDefault("sentiment", "negative")), "source_event", e.id()));
        Map<String, Object> ord = new LinkedHashMap<>();
        ord.put("total", amount(order.totals() == null ? null : order.totals().grandTotal()));
        ord.put("shipping_paid", amount(order.totals() == null ? null : order.totals().shipping()));
        input.put("order", ord);
        Map<String, Object> history = order.customer() == null || order.customer().history() == null ? Map.of() : order.customer().history();
        input.put("customer", Map.of("tier", order.customer() == null || order.customer().tier() == null ? "" : order.customer().tier(),
            "history", Map.of("appeasements_12m", history.getOrDefault("appeasements_12m", 0), "orders_12m", history.getOrDefault("orders_12m", 0))));
        input.put("request", Map.of("credit_usd", credit));
        input.put("actor", Map.of("limit_usd", budget));

        DecisionResult r = decisions.decide(DECISION, input, DecisionOptions.live("order", order.id(), ACTOR));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionRecordId", r.recordId() == null ? "" : r.recordId());
        payload.put("recommendation", r.value() == null ? "" : String.valueOf(r.value()));
        payload.put("creditUsd", credit);
        payload.put("creditSource", level.source());
        payload.put("segment", segment);
        payload.put("band", r.band().name());
        payload.put("action", r.action());
        payload.put("requiresHuman", r.requiresHuman());
        payload.put("sourceEventId", e.id());
        events.append("order", order.id(), new NewEvent(EVT_RECOMMENDED, payload, ACTOR).withCorrelation(order.id(), r.recordId()));
        return r;
    }

    private boolean alreadyHandled(String orderId, String eventId) {
        for (DecisionRecord r : decisions.forSubject("order", orderId)) {
            if (!DECISION.equals(r.decision()) || r.input() == null) continue;
            if (r.input().get("case") instanceof Map<?, ?> c && eventId.equals(c.get("source_event"))) return true;
        }
        return false;
    }

    private Optional<Double> level(String segment) {
        try {
            return facts.active(SubjectType.SEGMENT, segment, FactKey.APPEASEMENT_LEVEL)
                .filter(f -> f.value() instanceof TypedValue.NumberValue).map(f -> ((TypedValue.NumberValue) f.value()).value());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    static boolean isComplaint(Event e) {
        if ("cs.complaint".equals(e.type())) return true;
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        return Boolean.TRUE.equals(p.get("complaint")) || "complaint".equalsIgnoreCase(String.valueOf(p.get("reason")));
    }

    private static String orderIdOf(Event e) {
        Object o = e.payload() == null ? null : e.payload().getOrDefault("orderId", e.payload().get("order_id"));
        if (o != null) return String.valueOf(o);
        return "order".equals(e.aggregateType()) ? e.aggregateId() : null;
    }

    private static double amount(ai.intentoms.finance.model.Money m) {
        return m == null || m.amount() == null ? 0.0 : m.amount().setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
