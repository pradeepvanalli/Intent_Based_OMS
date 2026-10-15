package ai.intentoms.monitoring.exception;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.monitoring.playbook.PlaybookService;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns ledger facts into exceptions (FR-MON-03..05), delivered after commit by the outbox relay:
 * <ul>
 *   <li>{@code sla.breached} → exception {@code sla_breach} (details state, onBreach, ageSeconds); {@code onBreach: playbook.X}
 *       runs that playbook on the exception instead of the taxonomy default, {@code decision.X} runs the decision (e.g. {@code backorder_next_action}) and
 *       stores its record id (FR-SM-08, FR-MON-02).</li>
 *   <li>{@code order.transitioned} with trigger {@code event.store_rejected} / {@code event.short_pick} → classified exception
 *       (store_reject / inventory_short) (FR-SRC-07).</li>
 *   <li>{@code workflow.failed} → exception {@code system} (FR-WF-*).</li>
 *   <li>{@code decision.evaluated} escalated on T2/T3 → counted only; the escalation handler owns the human task.</li>
 * </ul>
 * Idempotent: every raise carries the source event id, so a re-delivered event finds its exception.
 */
@Component
public class MonitoringEventSubscriber implements EventSubscriber {
    public static final String TYPE_SLA_BREACH = "sla_breach";
    public static final String TYPE_SYSTEM = "system";
    private static final Logger log = LoggerFactory.getLogger(MonitoringEventSubscriber.class);
    private static final Set<String> REJECT_TRIGGERS = Set.of("event.store_rejected", "event.short_pick");
    private static final Actor ACTOR = Actor.system("monitoring");

    private final ExceptionService exceptions;
    private final PlaybookService playbooks;
    private final DecisionService decisions;
    private final DecisionDefinitionRepository definitions;
    private final OrderRepository orders;
    private final TimeSource time;
    private final Metrics metrics;

    public MonitoringEventSubscriber(ExceptionService exceptions, PlaybookService playbooks, DecisionService decisions, DecisionDefinitionRepository definitions,
                                     OrderRepository orders, TimeSource time, Metrics metrics) {
        this.exceptions = exceptions;
        this.playbooks = playbooks;
        this.decisions = decisions;
        this.definitions = definitions;
        this.orders = orders;
        this.time = time;
        this.metrics = metrics;
    }

    @Override
    public List<String> eventTypePrefixes() { return List.of("sla.breached", "order.transitioned", "workflow.failed", "decision.evaluated"); }

    @Override
    public void on(Event event) {
        switch (event.type()) {
            case "sla.breached" -> onSlaBreached(event);
            case "order.transitioned" -> onTransitioned(event);
            case "workflow.failed" -> onWorkflowFailed(event);
            case "decision.evaluated" -> onDecisionEvaluated(event);
            default -> { }
        }
    }

    void onSlaBreached(Event e) {
        Map<String, Object> p = e.payload();
        String entityType = str(p.get("entityType"));
        String entityId = str(p.get("entityId"));
        String state = str(p.get("state"));
        String onBreach = str(p.get("onBreach"));
        boolean orderScoped = "order".equals(e.aggregateType());
        String orderId = orderScoped ? e.aggregateId() : null;
        String lineId = "order_line".equals(entityType) && entityId != null && entityId.indexOf(':') > 0 ? entityId.substring(entityId.indexOf(':') + 1) : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", state);
        details.put("onBreach", onBreach == null ? "" : onBreach);
        details.put("ageSeconds", p.get("ageSeconds") instanceof Number n ? n.longValue() : 0L);
        details.put("entityType", entityType);
        details.put("entityId", entityId);
        if (p.get("dueAt") != null) details.put("dueAt", p.get("dueAt"));
        String message = "SLA breached: " + entityType + " " + entityId + " in state " + state + " for " + Duration.ofSeconds(((Number) details.get("ageSeconds")).longValue()).toMinutes() + " min";
        ExceptionRequest req = new ExceptionRequest("sla", "sla_breached_" + state, message, orderId, lineId, nodeOf(orderId, lineId), details);
        boolean fresh = exceptions.findBySourceEvent(e.id()).isEmpty();
        String playbookOverride = onBreach != null && onBreach.startsWith("playbook.") ? onBreach.substring("playbook.".length()) : null;
        ExceptionRecord exc = exceptions.raise(req, TYPE_SLA_BREACH, e.id(), playbookOverride);   // runs the onBreach playbook (or the taxonomy default) once
        if (!fresh) return;
        if (onBreach != null && onBreach.startsWith("decision.")) {
            runBreachDecision(exc, onBreach.substring("decision.".length()), orderId, lineId, state, details);
        }
        metrics.count("oms.monitoring.sla_breaches", "state", state == null ? "" : state);
    }

    private void runBreachDecision(ExceptionRecord exc, String decision, String orderId, String lineId, String state, Map<String, Object> details) {
        Optional<Order> order = orderId == null ? Optional.empty() : orders.find(orderId);
        Optional<OrderLine> line = order.flatMap(o -> lineId == null ? Optional.empty() : o.line(lineId));
        long ageSeconds = ((Number) details.get("ageSeconds")).longValue();
        Map<String, Object> lineMap = new LinkedHashMap<>();
        lineMap.put("sku", line.map(OrderLine::sku).orElse(""));
        lineMap.put("days_backordered", Math.max(0, ageSeconds / 86_400));
        lineMap.put("expected_restock_days", line.map(l -> l.attributes().get("expected_restock_days")).filter(v -> v instanceof Number).map(v -> ((Number) v).intValue()).orElse(0));
        lineMap.put("substitute_available", line.map(l -> Boolean.TRUE.equals(l.attributes().get("substitute_available"))).orElse(false));
        lineMap.put("state", state);
        Map<String, Object> orderMap = new LinkedHashMap<>();
        orderMap.put("channel", order.map(Order::channel).orElse("unknown"));
        orderMap.put("intent", order.map(o -> o.intent() == null || o.intent().policy() == null ? "unclear" : o.intent().policy()).orElse("unclear"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("line", lineMap);
        input.put("order", orderMap);
        input.put("customer", Map.of("tier", order.map(o -> o.customer() == null || o.customer().tier() == null ? "standard" : o.customer().tier()).orElse("standard")));
        input.put("network", Map.of("atp_elsewhere", 0));
        try {
            DecisionResult r = decisions.decide(decision, input, DecisionOptions.live(exc.aggregateType(), exc.aggregateId(), ACTOR));
            exceptions.attachDecision(exc.id(), r, Map.of("onBreachDecision", decision));
        } catch (RuntimeException ex) {
            log.warn("onBreach decision {} failed for exception {}: {}", decision, exc.id(), ex.toString());
        }
        playbooks.run(exc.id());
    }

    void onTransitioned(Event e) {
        String trigger = str(e.payload().get("trigger"));
        if (trigger == null || !REJECT_TRIGGERS.contains(trigger)) return;
        String entityType = str(e.payload().get("entityType"));
        String entityId = str(e.payload().get("entityId"));
        String orderId = e.aggregateId();
        String lineId = "order_line".equals(entityType) && entityId != null && entityId.indexOf(':') > 0 ? entityId.substring(entityId.indexOf(':') + 1) : null;
        String code = trigger.substring("event.".length());
        String message = "store_rejected".equals(code)
            ? "Store rejected the release" + (lineId == null ? "" : " for line " + lineId) + " (" + e.payload().get("from") + " -> " + e.payload().get("to") + ")"
            : "Short pick: inventory shortage" + (lineId == null ? "" : " on line " + lineId) + " (" + e.payload().get("from") + " -> " + e.payload().get("to") + ")";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("trigger", trigger);
        details.put("from", e.payload().get("from"));
        details.put("to", e.payload().get("to"));
        if (e.payload().get("qty") != null) details.put("qty", e.payload().get("qty"));
        exceptions.raise(new ExceptionRequest("wms", code, message, orderId, lineId, nodeOf(orderId, lineId), details), null, e.id());
    }

    void onWorkflowFailed(Event e) {
        Map<String, Object> p = e.payload();
        String type = str(p.get("type"));
        String businessKey = str(p.get("businessKey"));
        String orderId = type != null && type.startsWith("order.") && businessKey != null && orders.find(businessKey).isPresent() ? businessKey : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowId", e.aggregateId());
        details.put("workflowType", type == null ? "" : type);
        details.put("step", p.get("step") == null ? "" : p.get("step"));
        details.put("businessKey", businessKey == null ? "" : businessKey);
        String message = "Workflow " + type + " failed at step " + p.get("step") + ": " + p.get("reason");
        exceptions.raise(new ExceptionRequest("workflow", "workflow_failed", message, orderId, null, null, details), TYPE_SYSTEM, e.id());
    }

    void onDecisionEvaluated(Event e) {
        if (!DecisionResult.ESCALATE.equals(str(e.payload().get("action")))) return;
        String name = str(e.payload().get("decision"));
        int version = e.payload().get("version") instanceof Number n ? n.intValue() : 0;
        Tier tier = definitions.find(name, version).map(r -> r.definition().tier()).orElse(null);
        if (tier == Tier.T2 || tier == Tier.T3) metrics.count("oms.monitoring.decision_escalations", "decision", name == null ? "" : name, "tier", tier.name());
    }

    /** Node of the line's fulfilment group (or the order's primary node) when known. */
    private String nodeOf(String orderId, String lineId) {
        if (orderId == null) return null;
        Optional<Order> order = orders.find(orderId);
        if (order.isEmpty()) return null;
        Order o = order.get();
        if (lineId != null) {
            Optional<OrderLine> line = o.line(lineId);
            if (line.isPresent() && line.get().fulfilmentGroupId() != null) {
                for (FulfilmentGroup g : o.fulfilmentGroups()) if (g.id().equals(line.get().fulfilmentGroupId())) return g.nodeId();
            }
        }
        return o.fulfilmentGroups().isEmpty() ? null : o.fulfilmentGroups().get(0).nodeId();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    Instant now() { return time.now(); }
}
