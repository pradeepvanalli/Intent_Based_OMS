package ai.intentoms.orchestration.workflow;

import ai.intentoms.finance.model.Money;
import ai.intentoms.orchestration.config.OrchestrationConfig;
import ai.intentoms.orchestration.config.OrchestrationConfigKeys;
import ai.intentoms.orchestration.config.OrchestrationProperties;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.orchestration.integration.WmsConnector;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.IntentProfile;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.service.IntentService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.RetryPolicy;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowContext;
import ai.intentoms.workflow.model.WorkflowDefinition;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Steps every order workflow shares (FR-WF-01, FR-WF-02, UC-01..UC-04, UC-07): validation, intent inference, agent
 * verification, payment authorisation, sourcing with the margin-floor approval loop (GR-01, FR-SRC-04), backorders,
 * reservations, release to the WMS and cancellation, plus the saga compensations {@code void_auth} and
 * {@code release_reservations}. Subclasses add the fulfilment-specific steps (ship-to-home or pickup) and name the step
 * that follows {@code release}.
 *
 * <p>Determinism: every external effect runs in {@code ctx.activity}; because a step may be re-entered (store reject →
 * reserve → release again) activity names carry the sourcing {@code cycle} and per-step attempt counters kept in vars.
 */
public abstract class AbstractOrderWorkflow implements WorkflowDefinition {
    public static final String MARGIN_TASK_TYPE = "margin_floor_approval";
    public static final String MARGIN_QUEUE = "margin_floor_approvals";
    protected static final RetryPolicy RETRY = RetryPolicy.of(4, Duration.ofSeconds(1));
    protected static final RetryPolicy ONCE = RetryPolicy.none();
    static final Set<String> FINISHED_LINE = Set.of("shipped", "delivered", "picked_up", "cancelled");

    protected final OrderWorkflowSupport s;
    protected final SourcingService sourcing;
    protected final IntentService intents;
    protected final HumanTaskService tasks;
    protected final WmsConnector wms;
    protected final CustomerNotifier notifier;
    protected final TenantConfigStore config;
    protected final OrchestrationProperties props;
    private final ObjectProvider<AgentVerificationHook> agentHook;

    protected AbstractOrderWorkflow(OrderWorkflowSupport support, SourcingService sourcing, IntentService intents, HumanTaskService tasks, WmsConnector wms,
                                    CustomerNotifier notifier, TenantConfigStore config, OrchestrationProperties props, ObjectProvider<AgentVerificationHook> agentHook) {
        this.s = support;
        this.sourcing = sourcing;
        this.intents = intents;
        this.tasks = tasks;
        this.wms = wms;
        this.notifier = notifier;
        this.config = config;
        this.props = props;
        this.agentHook = agentHook;
    }

    private ai.intentoms.orchestration.fraud.PaymentRiskGate paymentRiskGate;

    /** Pre-capture fraud checks (FD-01/FD-02); optional so a workflow built without it simply skips them. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPaymentRiskGate(ai.intentoms.orchestration.fraud.PaymentRiskGate gate) { this.paymentRiskGate = gate; }

    /** Step entered after a successful release ({@code await_fulfilment} for ship-to-home, {@code await_pick} for BOPIS). */
    protected abstract String afterReleaseStep();

    /** Whether {@code infer_intent} runs (ship-to-home only; pickup orders have a fixed policy). */
    protected boolean infersIntent() { return true; }

    /** Fulfilment-specific steps merged into {@link #steps()}. */
    protected abstract Map<String, Step> fulfilmentSteps();

    @Override public String initialStep() { return "validate"; }
    @Override public boolean compensateOnFailure() { return true; }

    @Override
    public Map<String, Step> steps() {
        Map<String, Step> m = new LinkedHashMap<>();
        m.put("validate", this::validate);
        m.put("infer_intent", this::inferIntent);
        m.put("verify_agent", this::verifyAgent);
        m.put("on_agent_verification", this::onAgentVerification);
        m.put("payment_authorize", this::paymentAuthorize);
        m.put("source", this::source);
        m.put("margin_decision", this::marginDecision);
        m.put("margin_timeout", this::marginTimeout);
        m.put("backorder", this::backorder);
        m.put("backorder_resume", this::backorderResume);
        m.put("reserve", this::reserve);
        m.put("release", this::release);
        m.put("fulfilment_timeout", this::fulfilmentTimeout);
        m.put("cancel_order", this::cancelOrder);
        m.putAll(fulfilmentSteps());
        return m;
    }

    @Override
    public Map<String, Step> compensations() {
        Map<String, Step> m = new LinkedHashMap<>();
        m.put("void_auth", ctx -> {
            Map<String, Object> r = ctx.activity("void_auth", RETRY, () -> s.voidAuthorization(ctx.businessKey(), "compensation: " + ctx.vars().getOrDefault("failReason", "workflow failed")));
            ctx.log("Compensation void_auth", r);
            return Next.complete(r);
        });
        m.put("release_reservations", ctx -> {
            List<String> r = ctx.activity("release_reservations", RETRY, () -> s.releaseReservations(ctx.businessKey(), "compensation"));
            ctx.log("Compensation release_reservations", Map.of("released", r));
            return Next.complete(Map.of("released", r));
        });
        return m;
    }

    // ------------------------------------------------------------------ validate / intent / agent

    protected Next validate(WorkflowContext ctx) {
        Order order = order(ctx);
        ctx.vars().put("orderId", order.id());
        ctx.vars().put("orderType", order.orderType());
        ctx.vars().put("channel", order.channel());
        ctx.vars().put("cycle", 0);
        List<Map<String, Object>> moved = ctx.activity("order_validated", RETRY, () -> {
            List<Map<String, Object>> out = s.fireAllLines(order, "event.order_validated", Map.of());
            out.add(s.fireHeader(order.id(), "event.order_validated", Map.of()));
            return out;
        });
        ctx.log("Order validated; lines awaiting sourcing", Map.of("orderId", order.id(), "lines", order.lines().size(), "transitions", moved.size(), "channel", order.channel()));
        return Next.goTo(infersIntent() ? "infer_intent" : "verify_agent");
    }

    protected Next inferIntent(WorkflowContext ctx) {
        Order order = order(ctx);
        Map<String, Object> result = ctx.activity("infer_intent", RETRY, () -> {
            IntentProfile p = intents.inferIntent(order);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policy", p.policy());
            m.put("source", p.source());
            m.put("confidence", p.confidence());
            m.put("decisionRecordId", p.decisionRecordId() == null ? "" : p.decisionRecordId());
            m.put("splitOk", p.splitOk());
            m.put("isGift", p.isGift());
            return m;
        });
        ctx.vars().put("intent", result);
        ctx.log("Fulfilment intent resolved (FR-CAP-06)", result);
        return Next.goTo("verify_agent");
    }

    protected Next verifyAgent(WorkflowContext ctx) {
        Order order = order(ctx);
        List<Hold> holds = order.isAgentOrder() ? s.holdsOfType(order.id(), "agent_verification") : List.of();
        if (holds.isEmpty()) return Next.goTo("payment_authorize");
        String verdict = ctx.activity("agent_verification_hook", ONCE, () -> {
            AgentVerificationHook hook = agentHook.getIfAvailable();
            if (hook == null) return "none";
            Optional<Boolean> v = hook.verify(order);
            return v.map(b -> b ? "true" : "false").orElse("none");
        });
        ctx.log("Agent order verification (UC-09)", Map.of("agentId", String.valueOf(order.provenance().agentId()), "holds", holds.stream().map(Hold::id).toList(), "verdict", verdict));
        return switch (verdict) {
            case "true" -> releaseAgentHold(ctx, "verified by agent module");
            case "false" -> { ctx.vars().put("cancelReason", "agent_rejected"); yield Next.goTo("cancel_order"); }
            default -> Next.waitSignal(Set.of(Signals.AGENT_VERIFIED, Signals.AGENT_REJECTED), Duration.ofHours(24), "cancel_order", "on_agent_verification");
        };
    }

    protected Next onAgentVerification(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        if (Signals.AGENT_VERIFIED.equals(sig.name())) return releaseAgentHold(ctx, "signal agent_verified by " + sig.payload().getOrDefault("by", "ops"));
        ctx.vars().put("cancelReason", "agent_rejected");
        return Next.goTo("cancel_order");
    }

    private Next releaseAgentHold(WorkflowContext ctx, String resolution) {
        List<String> released = ctx.activity("release_agent_hold", RETRY, () -> {
            List<String> ids = new ArrayList<>();
            for (Hold h : s.holdsOfType(ctx.businessKey(), "agent_verification")) { s.holds().release(h.id(), resolution, OrderWorkflowSupport.ACTOR); ids.add(h.id()); }
            return ids;
        });
        ctx.log("Agent verification hold released", Map.of("holds", released));
        return Next.goTo("payment_authorize");
    }

    // ------------------------------------------------------------------ payment (FR-FIN-01)

    protected Next paymentAuthorize(WorkflowContext ctx) {
        Order order = order(ctx);
        ai.intentoms.orchestration.fraud.PaymentRiskGate gate = paymentRiskGate;
        if (gate != null) {
            // FD-01/FD-02 before the money moves: a risky order is held before release/capture, never cancelled here.
            Map<String, Object> risk = ctx.activity("payment_risk", RETRY, () -> gate.assess(order));
            ctx.vars().put("paymentRisk", risk);
            ctx.log("Payment risk assessed before authorisation (FD-01/FD-02)", risk);
        }
        ctx.compensation("void_auth");
        List<Map<String, Object>> auths = ctx.activity("authorize", RETRY, () -> s.authorizeTenders(order));
        ctx.vars().put("payments", auths);
        List<Map<String, Object>> declined = auths.stream().filter(a -> "FAILED".equals(a.get("status"))).toList();
        if (!declined.isEmpty()) {
            Map<String, Object> exc = ctx.activity("payment_declined_exception", RETRY, () -> s.raise("payment", "auth_declined",
                "Authorisation declined: " + declined.stream().map(a -> String.valueOf(a.get("failureCode"))).toList(), order.id(), null, null,
                Map.of("declined", declined, "total", order.totals().grandTotal().amount()), "payment"));
            ctx.log("Payment declined; failing with compensation (FR-WF-02)", Map.of("declined", declined, "exception", exc));
            ctx.vars().put("failReason", "payment_declined");
            return Next.fail("payment_declined", true);
        }
        ctx.log("Tenders authorised", Map.of("payments", auths));
        return Next.goTo("source");
    }

    // ------------------------------------------------------------------ sourcing (FR-SRC-02..04, UC-03)

    protected Next source(WorkflowContext ctx) {
        int cycle = OrderWorkflowSupport.intOf(ctx.vars().get("cycle"), 0) + 1;
        ctx.vars().put("cycle", cycle);
        ctx.vars().remove("reserveRetries");
        Order order = order(ctx);
        Map<String, Object> result = ctx.activity("source#" + cycle, RETRY, () -> {
            SourcingResult r = sourcing.source(order.id(), OrderWorkflowSupport.ACTOR);
            Map<String, Object> m = planSummary(r);
            m.put("floor", config.getDouble(ai.intentoms.platform.config.ConfigKeys.MARGIN_FLOOR, 0.12));
            if (r.sourced()) {
                Money[] costs = costs(r.plan());
                Order updated = s.orders().recomputeMargin(order.id(), costs[0].amount(), costs[1].amount(), costs[2].amount());
                m.put("marginRatio", updated.margin() == null ? r.plan().pnl().marginRatio() : updated.margin().marginRatio());
                m.put("approvalPresent", updated.margin() != null && updated.margin().approvalPresent());
            }
            return m;
        });
        ctx.vars().put("plan", result);
        String status = OrderWorkflowSupport.str(result.get("status"));
        ctx.log("Sourcing " + status, result);
        if ("SOURCED".equals(status)) return Next.goTo("reserve");
        if ("REQUIRES_APPROVAL".equals(status)) {
            Map<String, Object> task = ctx.activity("margin_task#" + cycle, RETRY, () -> createMarginTask(ctx, order, result));
            ctx.vars().put("marginTask", task);
            ctx.log("Plan below margin floor; waiting for finance approval (GR-01, FR-SRC-04)", task);
            return Next.waitTask(OrderWorkflowSupport.str(task.get("taskId")), "margin_decision", Duration.ofHours(48), "margin_timeout");
        }
        return Next.goTo("backorder");
    }

    private Map<String, Object> createMarginTask(WorkflowContext ctx, Order order, Map<String, Object> plan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("plan", plan.get("plan"));
        payload.put("alternatives", plan.get("alternatives"));
        payload.put("marginRatio", plan.get("marginRatio"));
        payload.put("floor", plan.get("floor"));
        payload.put("reasons", plan.get("reasons"));
        payload.put("options", List.of("approved", "alternative", "rejected"));
        HumanTask t = tasks.create(new TaskRequest(MARGIN_TASK_TYPE, MARGIN_QUEUE, "order", order.id(),
            "Approve below-floor plan for order " + order.id() + String.format(java.util.Locale.ROOT, " (margin %.1f%% < floor %.1f%%)", num(plan.get("marginRatio")) * 100, num(plan.get("floor")) * 100),
            payload, Duration.ofHours(2), Set.of("finance"), 5, ctx.workflowId()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskId", t.id());
        details.put("marginRatio", plan.get("marginRatio"));
        details.put("floor", plan.get("floor"));
        details.put("planId", OrderWorkflowSupport.map(plan.get("plan")).get("planId"));
        Map<String, Object> exc = s.raise("sourcing", "below_margin_floor", "Chosen plan is below the contribution-margin floor; finance approval task " + t.id(),
            order.id(), null, null, details, "margin_floor", "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", t.id());
        out.put("queue", t.queue());
        out.put("exceptionId", exc.get("exceptionId"));
        return out;
    }

    protected Next marginDecision(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        Map<String, Object> task = OrderWorkflowSupport.map(ctx.vars().get("marginTask"));
        Map<String, Object> plan = OrderWorkflowSupport.map(ctx.vars().get("plan"));
        MarginDecision.Outcome decision = MarginDecision.decide(OrderWorkflowSupport.str(sig.payload().get("outcome")), OrderWorkflowSupport.map(sig.payload().get("details")),
            OrderWorkflowSupport.listOfMaps(plan.get("alternatives")));
        String taskId = OrderWorkflowSupport.str(task.get("taskId"));
        String reason = OrderWorkflowSupport.str(sig.payload().getOrDefault("reason", decision.reason()));
        ctx.log("Margin-floor decision by " + sig.payload().getOrDefault("completedBy", "human"), Map.of("outcome", String.valueOf(sig.payload().get("outcome")), "kind", decision.kind().name(), "policy", String.valueOf(decision.policy())));
        switch (decision.kind()) {
            case APPROVE -> {
                ctx.activity("mark_approval:" + taskId, RETRY, () -> {
                    s.orders().markBelowFloorApproval(ctx.businessKey(), taskId);
                    s.resolveException(OrderWorkflowSupport.str(task.get("exceptionId")), "approved", reason);
                    return Map.of("approved", true, "taskId", taskId);
                });
                return Next.goTo("source");
            }
            case ALTERNATIVE -> {
                ctx.activity("apply_alternative:" + taskId, RETRY, () -> {
                    Order o = order(ctx);
                    IntentProfile prev = o.intent();
                    IntentProfile alt = new IntentProfile(decision.policy(), 1.0, "approval_alternative", prev == null ? List.of() : prev.explicitChoices(), prev == null ? null : prev.decisionRecordId(),
                        prev == null ? null : prev.splitOk(), prev == null ? null : prev.isGift());
                    s.orders().update(o.id(), x -> x.withIntent(alt), new NewEvent("order.intent_resolved", Map.of("policy", decision.policy(), "source", "approval_alternative", "taskId", taskId, "reason", String.valueOf(reason)), OrderWorkflowSupport.ACTOR));
                    s.resolveException(OrderWorkflowSupport.str(task.get("exceptionId")), "alternative", reason);
                    return Map.of("policy", decision.policy());
                });
                return Next.goTo("source");
            }
            default -> {
                s.resolveException(OrderWorkflowSupport.str(task.get("exceptionId")), "rejected", reason);
                ctx.vars().put("cancelReason", "margin_floor_rejected");
                return Next.goTo("cancel_order");
            }
        }
    }

    protected Next marginTimeout(WorkflowContext ctx) {
        Map<String, Object> task = OrderWorkflowSupport.map(ctx.vars().get("marginTask"));
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("marginTimeouts"), 0) + 1;
        ctx.vars().put("marginTimeouts", n);
        Map<String, Object> exc = ctx.activity("margin_timeout#" + n, RETRY, () -> s.raise("workflow", "margin_approval_timeout", "Margin-floor approval task " + task.get("taskId") + " expired without a decision",
            ctx.businessKey(), null, null, Map.of("taskId", String.valueOf(task.get("taskId")), "timeouts", n), "margin_floor"));
        ctx.log("Margin approval timed out; re-sourcing to open a fresh task", exc);
        return Next.goTo("source");
    }

    // ------------------------------------------------------------------ backorder (UC-06)

    protected Next backorder(WorkflowContext ctx) {
        int cycle = OrderWorkflowSupport.intOf(ctx.vars().get("cycle"), 0);
        Order order = order(ctx);
        Map<String, Object> result = ctx.activity("backorder#" + cycle, RETRY, () -> {
            List<Map<String, Object>> moved = new ArrayList<>();
            for (OrderLine l : order.lines()) {
                if (s.qtyIn(order, l.lineId(), "awaiting_sourcing") > 0)
                    moved.add(s.fireLine(order.id(), l.lineId(), "decision.sourcing_plan", null, Map.of("plan", Map.of("result", "no_feasible_node", "margin", 0.0), "approval", Map.of("present", false))));
            }
            Map<String, Object> exc = s.raise("sourcing", "no_feasible_node", "No node can fulfil the order; lines backordered", order.id(), null, null, Map.of("lines", moved.size()), null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transitions", moved);
            m.put("exception", exc);
            return m;
        });
        ctx.log("Lines backordered; waiting for inventory (FR-SRC-07)", result);
        return Next.waitSignal(Set.of(Signals.INVENTORY_AVAILABLE, Signals.CANCEL_REQUESTED), Duration.ofDays(30), "cancel_order", "backorder_resume");
    }

    protected Next backorderResume(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        if (Signals.CANCEL_REQUESTED.equals(sig.name())) { ctx.vars().put("cancelReason", "cancel_requested"); return Next.goTo("cancel_order"); }
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("backorderResumes"), 0) + 1;
        ctx.vars().put("backorderResumes", n);
        Order order = order(ctx);
        List<Map<String, Object>> moved = ctx.activity("inventory_available#" + n, RETRY, () -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (OrderLine l : order.lines()) if (s.qtyIn(order, l.lineId(), "backordered") > 0) out.add(s.fireLine(order.id(), l.lineId(), "event.inventory_available", null, Map.of()));
            return out;
        });
        ctx.log("Inventory available; re-sourcing backordered lines", Map.of("transitions", moved));
        return Next.goTo("source");
    }

    // ------------------------------------------------------------------ reserve (FR-INV-02)

    protected Next reserve(WorkflowContext ctx) {
        int cycle = OrderWorkflowSupport.intOf(ctx.vars().get("cycle"), 0);
        int retry = OrderWorkflowSupport.intOf(ctx.vars().get("reserveRetries"), 0);
        ctx.compensation("release_reservations");
        Order order = order(ctx);
        Map<String, Object> res = ctx.activity("reserve#" + cycle + "." + retry, RETRY, () -> s.reserveForGroups(order));
        if (Boolean.TRUE.equals(res.get("ok"))) {
            ctx.log("Inventory reserved per package", res);
            return Next.goTo("release");
        }
        Map<String, Object> shortage = OrderWorkflowSupport.map(res.get("shortage"));
        String nodeId = OrderWorkflowSupport.str(shortage.get("nodeId"));
        ctx.log("Reservation failed (insufficient ATP)", res);
        if (retry >= 1 || nodeId == null) return Next.goTo("backorder");
        Map<String, Object> re = ctx.activity("resource_insufficient#" + cycle + "." + retry, RETRY, () -> {
            try { return planSummary(sourcing.resource(order.id(), nodeId, "insufficient_atp", OrderWorkflowSupport.ACTOR)); }
            catch (ConflictException e) { return Map.of("status", "NO_ACTIVE_GROUP", "error", String.valueOf(e.getMessage())); }
        });
        ctx.vars().put("reserveRetries", retry + 1);
        ctx.log("Re-sourced after insufficient ATP at " + nodeId, re);
        if ("SOURCED".equals(re.get("status"))) { ctx.vars().put("plan", re); return Next.goTo("reserve"); }
        if ("REQUIRES_APPROVAL".equals(re.get("status"))) return Next.goTo("source");
        return Next.goTo("backorder");
    }

    // ------------------------------------------------------------------ release (GR-01 guard, FR-SM-07 holds)

    protected Next release(WorkflowContext ctx) {
        int attempt = OrderWorkflowSupport.intOf(ctx.vars().get("releaseAttempts"), 0) + 1;
        ctx.vars().put("releaseAttempts", attempt);
        Order order = order(ctx);
        double margin = order.margin() == null ? 1.0 : order.margin().marginRatio();
        boolean approval = order.margin() != null && order.margin().approvalPresent();
        Map<String, Object> vars = Map.of("plan", Map.of("margin", margin, "result", "ok"), "approval", Map.of("present", approval));
        Map<String, Object> result = ctx.activity("release_lines#" + attempt, RETRY, () -> {
            List<Map<String, Object>> moved = new ArrayList<>();
            List<String> blocked = new ArrayList<>();
            for (FulfilmentGroup g : OrderWorkflowSupport.activeGroups(order)) {
                for (FulfilmentGroup.Allocation a : g.allocations()) {
                    if (s.qtyIn(order, a.lineId(), "awaiting_sourcing") <= 0) continue;
                    Map<String, Object> t = s.fireLine(order.id(), a.lineId(), "decision.sourcing_plan", a.qty(), vars);
                    moved.add(t);
                    if (!Boolean.TRUE.equals(t.get("applied"))) blocked.add(a.lineId() + ": " + t.get("blockedBy"));
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transitions", moved);
            m.put("blocked", blocked);
            m.put("margin", margin);
            m.put("approvalPresent", approval);
            return m;
        });
        List<?> blocked = (List<?>) result.getOrDefault("blocked", List.of());
        if (!blocked.isEmpty()) {
            List<Hold> holds = s.holdsBlocking(order.id(), "release");
            if (!holds.isEmpty()) {
                ctx.log("Release blocked by hold; waiting for hold_released (FR-SM-07)", Map.of("holds", holds.stream().map(h -> h.type() + ":" + h.id()).toList(), "blocked", blocked));
                return Next.waitSignal(Set.of(Signals.HOLD_RELEASED, Signals.CANCEL_REQUESTED), Duration.ofDays(7), "cancel_order", "release");
            }
            if (ctx.signal().map(sig -> Signals.CANCEL_REQUESTED.equals(sig.name())).orElse(false)) { ctx.vars().put("cancelReason", "cancel_requested"); return Next.goTo("cancel_order"); }
            ctx.vars().put("failReason", "release_blocked");
            ctx.log("Release blocked by guard; failing with compensation", result);
            return Next.fail("release_blocked: " + blocked, true);
        }
        Map<String, Object> sent = ctx.activity("wms_release#" + attempt, RETRY, () -> {
            Order fresh = order(ctx);
            List<Map<String, Object>> acks = new ArrayList<>();
            Set<String> released = new LinkedHashSet<>();
            for (FulfilmentGroup g : OrderWorkflowSupport.activeGroups(fresh)) {
                if (!"planned".equalsIgnoreCase(g.status()) && !"released".equalsIgnoreCase(g.status())) continue;
                Map<String, Object> ack = new LinkedHashMap<>(wms.sendRelease(fresh, g));
                ack.put("fulfilmentGroupId", g.id());
                acks.add(ack);
                released.add(g.id());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fulfilmentGroupIds", new ArrayList<>(released));
            payload.put("releases", acks);
            s.markGroups(fresh.id(), released, "released", new NewEvent("order.released_to_wms", payload, OrderWorkflowSupport.ACTOR, null, fresh.id(), Map.of()));
            return payload;
        });
        ctx.log("Lines released and handed to the WMS (UC-03)", sent);
        return Next.goTo(afterReleaseStep());
    }

    // ------------------------------------------------------------------ timeouts (FR-MON-05)

    protected Next fulfilmentTimeout(WorkflowContext ctx) {
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("fulfilmentTimeouts"), 0) + 1;
        ctx.vars().put("fulfilmentTimeouts", n);
        Order order = order(ctx);
        String node = OrderWorkflowSupport.activeGroups(order).stream().map(FulfilmentGroup::nodeId).findFirst().orElse(null);
        Map<String, Object> exc = ctx.activity("fulfilment_timeout#" + n, RETRY, () -> s.raise("workflow", "fulfilment_timeout",
            "No fulfilment event from the WMS within " + fulfilmentTimeout() + " (occurrence " + n + ")", order.id(), null, node, Map.of("timeouts", n, "waitedFor", fulfilmentTimeout().toString()), null));
        ctx.log("Fulfilment timeout; exception raised, still waiting", exc);
        return Next.goTo(afterReleaseStep());
    }

    protected Duration fulfilmentTimeout() { return OrchestrationConfig.duration(config, OrchestrationConfigKeys.FULFILMENT_TIMEOUT, props.fulfilmentTimeout()); }

    // ------------------------------------------------------------------ cancel (UC-07)

    protected Next cancelOrder(WorkflowContext ctx) {
        Order order = order(ctx);
        String reason = OrderWorkflowSupport.str(ctx.vars().getOrDefault("cancelReason", ctx.signal().map(Signal::name).orElse("cancelled")));
        Map<String, Object> result = ctx.activity("cancel_order", RETRY, () -> cancelEverything(order, reason));
        ctx.log("Order cancelled", result);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "cancelled");
        out.put("reason", reason);
        return Next.complete(out);
    }

    /** Cancels open units, voids uncaptured funds, releases reservations and WMS releases; JSON-friendly summary. */
    protected Map<String, Object> cancelEverything(Order order, String reason) {
        List<Map<String, Object>> moved = new ArrayList<>();
        for (OrderLine l : order.lines()) {
            EntityStatus st = s.lineStatus(order.id(), l.lineId());
            if (OrderWorkflowSupport.openQty(st) > 0) moved.add(s.fireLine(order.id(), l.lineId(), "api.cancel_line", null, Map.of()));
        }
        moved.add(s.fireHeader(order.id(), "api.cancel_order", Map.of()));
        List<String> released = s.releaseReservations(order.id(), "order cancelled: " + reason);
        boolean voided = false;
        if (!s.captured(order.id(), order.currency()).isPositive()) { s.voidAuthorization(order.id(), "order cancelled: " + reason); voided = true; }
        Set<String> groups = new LinkedHashSet<>();
        for (FulfilmentGroup g : OrderWorkflowSupport.activeGroups(order)) { wms.cancelRelease(order, g, reason); groups.add(g.id()); }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("by", "workflow");
        payload.put("reservationsReleased", released);
        payload.put("authVoided", voided);
        s.markGroups(order.id(), groups, "cancelled", new NewEvent("order.cancelled", payload, OrderWorkflowSupport.ACTOR, null, order.id(), Map.of()));
        Map<String, Object> m = new LinkedHashMap<>(payload);
        m.put("transitions", moved);
        m.put("groupsCancelled", new ArrayList<>(groups));
        return m;
    }

    // ------------------------------------------------------------------ helpers

    protected Order order(WorkflowContext ctx) { return s.orders().get(ctx.businessKey()); }

    /** JSON-friendly summary of a sourcing result (plan, alternatives, reasons). */
    static Map<String, Object> planSummary(SourcingResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().name());
        m.put("reasons", r.reasons());
        m.put("decisionRecordId", r.decisionRecordId() == null ? "" : r.decisionRecordId());
        if (r.plan() != null) {
            m.put("plan", plan(r.plan()));
            m.put("marginRatio", r.plan().pnl() == null ? null : r.plan().pnl().marginRatio());
            m.put("meetsMarginFloor", r.plan().meetsMarginFloor());
        }
        List<Map<String, Object>> alts = new ArrayList<>();
        for (SourcingPlan p : r.alternatives()) alts.add(plan(p));
        m.put("alternatives", alts);
        return m;
    }

    static Map<String, Object> plan(SourcingPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.id());
        m.put("policy", p.policy());
        m.put("score", p.score());
        m.put("totalCost", p.totalCost() == null ? null : p.totalCost().amount());
        m.put("marginRatio", p.pnl() == null ? null : p.pnl().marginRatio());
        m.put("meetsMarginFloor", p.meetsMarginFloor());
        m.put("eta", p.latestEta() == null ? null : p.latestEta().toString());
        m.put("onTimeProbability", p.onTimeProbability());
        List<Map<String, Object>> pks = new ArrayList<>();
        for (PlanPackage pk : p.packages()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("nodeId", pk.nodeId());
            x.put("eta", pk.eta() == null ? null : pk.eta().toString());
            x.put("onTimeProbability", pk.onTimeProbability());
            x.put("transitDays", pk.transitDays());
            x.put("cost", pk.totalCost().amount());
            x.put("allocations", pk.allocations().stream().<Map<String, Object>>map(a -> Map.of("lineId", a.lineId(), "sku", a.sku(), "qty", a.qty())).toList());
            pks.add(x);
        }
        m.put("packages", pks);
        m.put("unsourcedLineIds", p.unsourcedLineIds());
        return m;
    }

    /** Shipping, labour and packaging cost of a plan (for the margin re-computation, FR-FIN-04). */
    static Money[] costs(SourcingPlan p) {
        String ccy = p.totalCost() == null ? "USD" : p.totalCost().currency();
        Money ship = Money.zero(ccy), labour = Money.zero(ccy), pack = Money.zero(ccy);
        for (PlanPackage pk : p.packages()) {
            ship = ship.add(pk.shippingCost()).add(pk.landedCost()).add(pk.handlingCost());
            labour = labour.add(pk.labourCost());
            pack = pack.add(pk.packagingCost());
        }
        return new Money[]{ship, labour, pack};
    }

    protected static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    /** Per-line quantities from a WMS payload ({@code lines:[{lineId, qty}]}); empty when absent. */
    protected static Map<String, Integer> payloadLines(Map<String, Object> payload) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, Object> l : OrderWorkflowSupport.listOfMaps(payload.get("lines"))) {
            String id = OrderWorkflowSupport.str(l.get("lineId"));
            if (id != null) out.merge(id, OrderWorkflowSupport.intOf(l.get("qty"), 0), Integer::sum);
        }
        return out;
    }

    /** The fulfilment group a WMS event refers to: by id, else by node, else the first active group. */
    protected static FulfilmentGroup resolveGroup(Order order, Map<String, Object> payload) {
        String gid = OrderWorkflowSupport.str(payload.get("fulfilmentGroupId"));
        String node = OrderWorkflowSupport.str(payload.get("nodeId"));
        List<FulfilmentGroup> active = OrderWorkflowSupport.activeGroups(order);
        if (gid != null) for (FulfilmentGroup g : order.fulfilmentGroups()) if (g.id().equals(gid)) return g;
        if (node != null) for (FulfilmentGroup g : active) if (node.equals(g.nodeId())) return g;
        return active.isEmpty() ? (order.fulfilmentGroups().isEmpty() ? null : order.fulfilmentGroups().get(0)) : active.get(0);
    }

    /** Units of each line this event covers: explicit {@code lines}, else the group's allocations. */
    protected static Map<String, Integer> affectedLines(Order order, FulfilmentGroup group, Map<String, Object> payload) {
        Map<String, Integer> explicit = payloadLines(payload);
        if (!explicit.isEmpty()) return explicit;
        Map<String, Integer> out = new LinkedHashMap<>();
        if (group != null) for (FulfilmentGroup.Allocation a : group.allocations()) out.merge(a.lineId(), a.qty(), Integer::sum);
        else for (OrderLine l : order.lines()) out.put(l.lineId(), l.qty());
        return out;
    }

    protected static Map<String, Object> jsonPayload(Signal sig) { return sig.payload() == null ? new LinkedHashMap<>() : Json.toMap(sig.payload()); }

    protected static BigDecimal bd(Object o) { return o instanceof BigDecimal b ? b : o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO; }
}
