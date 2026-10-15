package ai.intentoms.monitoring.playbook;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionRepository;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes playbooks against exceptions (FR-MON-04). Each step passes the gates in {@link PlaybookGate} (plus
 * {@code requiresDecision}: run that decision and gate on its confidence / act band) and is dispatched to the
 * {@link PlaybookAction} with the matching name; missing handlers are recorded as {@code skipped: no handler} and
 * handler errors as {@code failed} — a playbook run never throws. Every step is appended to
 * {@code exception.playbook_steps} (UI contract item 10) and to the ledger as {@code exception.playbook_step}.
 * {@link #run(String)} is idempotent: an exception whose playbook already ran returns the recorded steps.
 */
@Service
public class PlaybookService {
    private static final Logger log = LoggerFactory.getLogger(PlaybookService.class);
    private static final Actor ACTOR = Actor.system("playbook");

    private final ExceptionRepository exceptions;
    private final PlaybookRepository playbooks;
    private final ObjectProvider<PlaybookAction> actionBeans;
    private final OrderRepository orders;
    private final DecisionService decisions;
    private final PlaybookGate gate;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;
    private final TenantConfigStore config;

    public PlaybookService(ExceptionRepository exceptions, PlaybookRepository playbooks, ObjectProvider<PlaybookAction> actionBeans, OrderRepository orders,
                           DecisionService decisions, CelEngine cel, EventStore events, TimeSource time, Metrics metrics, TenantConfigStore config) {
        this.exceptions = exceptions;
        this.playbooks = playbooks;
        this.actionBeans = actionBeans;
        this.orders = orders;
        this.decisions = decisions;
        this.gate = new PlaybookGate(cel);
        this.events = events;
        this.time = time;
        this.metrics = metrics;
        this.config = config;
    }

    /** Run the exception's playbook once; already-run exceptions return their recorded steps. */
    public List<PlaybookStep> run(String exceptionId) { return run(exceptionId, null, false); }

    /** Force a fresh run of the exception's playbook (operator action {@code POST /exceptions/{id}/run-playbook}). */
    public List<PlaybookStep> rerun(String exceptionId) { return run(exceptionId, null, true); }

    /** Run a specific playbook on the exception (SLA {@code onBreach: playbook.<name>}), recording it as the exception's playbook. */
    public List<PlaybookStep> runNamed(String exceptionId, String playbookName) { return run(exceptionId, playbookName, true); }

    /** Run with the in-memory classification result so gates see its confidence (raise path). */
    public List<PlaybookStep> run(ExceptionRecord exception, DecisionResult classification) {
        if (exception.playbookRunAt() != null) return exception.playbookSteps();
        return execute(exception, exception.playbook(), classification);
    }

    private List<PlaybookStep> run(String exceptionId, String playbookName, boolean force) {
        ExceptionRecord exc = exceptions.get(exceptionId);
        if (!force && exc.playbookRunAt() != null) return exc.playbookSteps();
        return execute(exc, playbookName == null ? exc.playbook() : playbookName, classificationOf(exc));
    }

    public List<Playbook> list() { return playbooks.list(); }
    public Optional<Playbook> find(String name) { return playbooks.find(name); }
    public Playbook get(String name) { return playbooks.find(name).orElseThrow(() -> new NotFoundException("Playbook " + name + " not found")); }

    public Playbook save(Playbook p, Actor actor) {
        Playbook saved = playbooks.upsert(p);
        events.append("config", "playbook:" + p.name(), new NewEvent("playbook.changed", Map.of("name", p.name(), "steps", p.steps().size()), actor));
        return saved;
    }

    public Map<String, Object> spendLimits() { return playbooks.spendLimits(); }

    /** Handler names currently registered (built-ins plus orchestration's). */
    public List<String> registeredActions() { return actionBeans.stream().map(PlaybookAction::action).sorted().toList(); }

    // ------------------------------------------------------------------ execution

    private List<PlaybookStep> execute(ExceptionRecord exc, String playbookName, DecisionResult classification) {
        Instant start = time.now();
        if (playbookName == null || playbookName.isBlank()) {
            log.debug("Exception {} has no playbook", exc.id());
            exceptions.update(exc.withSteps(List.of(), start));
            return List.of();
        }
        Optional<Playbook> pb = playbooks.find(playbookName);
        if (pb.isEmpty()) {
            PlaybookStep missing = new PlaybookStep(playbookName, PlaybookStep.SKIPPED, "playbook not found", "missing", start);
            exceptions.update(exc.withPlaybook(playbookName).withSteps(List.of(missing), start));
            metrics.count("oms.monitoring.playbook_missing", "playbook", playbookName);
            return List.of(missing);
        }
        Map<String, PlaybookAction> handlers = new HashMap<>();
        actionBeans.forEach(a -> handlers.putIfAbsent(a.action(), a));
        Optional<Order> order = exc.orderId() == null ? Optional.empty() : orders.find(exc.orderId());
        Map<String, Object> spendLimits = playbooks.spendLimits();
        ExceptionRecord current = exceptions.update(exc.withPlaybook(playbookName).withSteps(List.of(), null));
        List<PlaybookStep> recorded = new ArrayList<>();
        boolean previousFailed = false;
        for (Map<String, Object> step : pb.get().steps()) {
            String action = String.valueOf(step.get("action"));
            PlaybookContext ctx = new PlaybookContext(current, order, classification, spendLimits, ACTOR);
            PlaybookStep result = runStep(ctx, step, action, handlers, previousFailed);
            recorded.add(result);
            if (PlaybookStep.FAILED.equals(result.status())) previousFailed = true;
            current = exceptions.get(current.id()).withSteps(List.copyOf(recorded), null);
            current = exceptions.update(current);
            appendStepEvent(current, playbookName, result);
            metrics.count("oms.monitoring.playbook_step", "action", action, "status", result.status());
        }
        exceptions.update(current.withSteps(List.copyOf(recorded), time.now()));
        metrics.record("oms.monitoring.playbook_run", Duration.between(start, time.now()), "playbook", playbookName);
        return recorded;
    }

    private PlaybookStep runStep(PlaybookContext ctx, Map<String, Object> step, String action, Map<String, PlaybookAction> handlers, boolean previousFailed) {
        Instant now = time.now();
        PlaybookAction handler = handlers.get(action);
        if (handler == null) return new PlaybookStep(action, PlaybookStep.SKIPPED, "no handler", "no_handler", now);
        double confidence = ctx.confidence();
        String decisionNote = null;
        Object requires = step.get(PlaybookGate.REQUIRES_DECISION);
        if (requires != null && !String.valueOf(requires).isBlank()) {
            DecisionResult r = runGateDecision(String.valueOf(requires), ctx);
            if (r == null) return new PlaybookStep(action, PlaybookStep.SKIPPED, "decision " + requires + " unavailable", PlaybookGate.REQUIRES_DECISION, now);
            confidence = r.confidence();
            decisionNote = requires + "=" + r.value() + " (" + r.recordId() + ")";
            if (r.requiresHuman() || DecisionResult.BLOCKED.equals(r.action()))
                return new PlaybookStep(action, PlaybookStep.SKIPPED, decisionNote + " requires a person", PlaybookGate.REQUIRES_DECISION, now);
            if (step.get(PlaybookGate.MIN_CONFIDENCE) == null && !r.acts())
                return new PlaybookStep(action, PlaybookStep.SKIPPED, decisionNote + " outside act band", PlaybookGate.REQUIRES_DECISION, now);
        }
        PlaybookGate.Verdict verdict = gate.evaluate(step, confidence, celVars(ctx, now), previousFailed, ctx.spendLimits());
        if (!verdict.pass()) return new PlaybookStep(action, PlaybookStep.SKIPPED, verdict.detail(), verdict.gatedBy(), now);
        try {
            PlaybookStepResult res = handler.run(ctx, step);
            String detail = decisionNote == null ? res.detail() : res.detail() + "; " + decisionNote;
            return new PlaybookStep(action, res.status(), detail, null, now);
        } catch (RuntimeException e) {
            log.warn("Playbook action {} failed for exception {}: {}", action, ctx.exception().id(), e.toString());
            return new PlaybookStep(action, PlaybookStep.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null, now);
        }
    }

    private DecisionResult runGateDecision(String name, PlaybookContext ctx) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("exception", exceptionMap(ctx.exception(), time.now()));
            input.put("order", orderMap(ctx.order(), ctx.exception()));
            if (BreachRiskInput.matches(name)) input.putAll(BreachRiskInput.build(ctx.order(), ctx.exception(), time.now(), config));
            return decisions.decide(name, input, DecisionOptions.live(ctx.exception().aggregateType(), ctx.exception().aggregateId(), ACTOR));
        } catch (RuntimeException e) {
            log.warn("Gate decision {} failed: {}", name, e.toString());
            return null;
        }
    }

    private void appendStepEvent(ExceptionRecord exc, String playbook, PlaybookStep step) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exceptionId", exc.id());
        payload.put("playbook", playbook);
        payload.put("action", step.action());
        payload.put("status", step.status());
        if (step.detail() != null) payload.put("detail", step.detail());
        if (step.gatedBy() != null) payload.put("gatedBy", step.gatedBy());
        events.append(exc.aggregateType(), exc.aggregateId(), new NewEvent("exception.playbook_step", payload, ACTOR));
    }

    /** Rebuild the classification result from its stored record for reruns (confidence and value are what gates need). */
    private DecisionResult classificationOf(ExceptionRecord exc) {
        if (exc.classificationRecordId() == null) return null;
        try {
            DecisionRecord r = decisions.record(exc.classificationRecordId());
            return new DecisionResult(r.id(), r.decision(), r.version(), r.layer(), r.band(), r.effectiveValue(), r.answers(), r.confidence(), r.action(), r.requiresHuman(),
                r.escalationId(), r.rulesFired(), r.reasonCodes(), r.latencyMs(), r.costUsd(), r.provider(), r.modelVersion(), r.details());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ CEL variables

    Map<String, Object> celVars(PlaybookContext ctx, Instant now) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("exception", exceptionMap(ctx.exception(), now));
        vars.put("order", orderMap(ctx.order(), ctx.exception()));
        vars.put("tenant", config.celTenantMap());
        vars.put("now", now);
        return vars;
    }

    static Map<String, Object> exceptionMap(ExceptionRecord e, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("type", e.type());
        m.put("severity", e.severity());
        m.put("queue", e.queue());
        m.put("code", e.code());
        m.put("source", e.source());
        m.put("status", e.status());
        m.put("occurrences", (long) e.occurrences());
        m.put("age_minutes", e.createdAt() == null ? 0L : Math.max(0L, Duration.between(e.createdAt(), now).toMinutes()));
        m.put("order_id", e.orderId() == null ? "" : e.orderId());
        m.put("line_id", e.lineId() == null ? "" : e.lineId());
        m.put("node_id", e.nodeId() == null ? "" : e.nodeId());
        m.put("details", e.details());
        return m;
    }

    static Map<String, Object> orderMap(Optional<Order> order, ExceptionRecord e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (order.isEmpty()) {
            m.put("id", e.orderId() == null ? "" : e.orderId());
            return m;
        }
        Order o = order.get();
        m.put("id", o.id());
        m.put("channel", o.channel());
        m.put("status", o.status());
        m.put("order_type", o.orderType());
        m.put("ship_method", o.shipMethod() == null ? "" : o.shipMethod());
        m.put("line_count", (long) o.lines().size());
        m.put("agent_order", o.isAgentOrder());
        m.put("intent", o.intent() == null || o.intent().policy() == null ? "" : o.intent().policy());
        m.put("customer_tier", o.customer() == null || o.customer().tier() == null ? "" : o.customer().tier());
        m.put("total", o.totals() == null || o.totals().grandTotal() == null ? o.merchandiseTotal().amount().doubleValue() : o.totals().grandTotal().amount().doubleValue());
        return m;
    }
}
