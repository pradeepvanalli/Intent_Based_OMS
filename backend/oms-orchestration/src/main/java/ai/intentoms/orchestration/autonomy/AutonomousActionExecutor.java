package ai.intentoms.orchestration.autonomy;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.order.model.Order;
import ai.intentoms.orchestration.actions.ActionSupport;
import ai.intentoms.orchestration.actions.ContactCustomerAction;
import ai.intentoms.orchestration.actions.ReauthorizeAction;
import ai.intentoms.orchestration.actions.RepromiseAction;
import ai.intentoms.orchestration.actions.ResourceAction;
import ai.intentoms.orchestration.escalation.HumanTaskEscalationHandler;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps a {@code stuck_order_diagnosis} result to one of the existing orchestration remediation actions and executes
 * it for real when every guardrail passes, or raises a human task with the full diagnosis attached otherwise
 * (Agentic Control Tower). The decision itself is never re-run or second-guessed here: {@link AnomalyScanner} already
 * obtained {@code result} through the normal {@code DecisionService.decide()} path, so every autonomous action is
 * traceable from a decision record exactly like any other decision (GR-13 spirit).
 *
 * <p>Band handling: when the decision already required a human (ESCALATE, or T2 CONFIRM with no L2 verdict) the
 * runtime's own {@link HumanTaskEscalationHandler} already opened the task — this class only looks it up to surface
 * it on the anomaly feed. When the decision reached ACT, this class maps the value to an action, checks the
 * guardrails and either executes it through the action's normal service call or opens a task itself, naming which
 * guardrail blocked it.</p>
 */
@Component
public class AutonomousActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(AutonomousActionExecutor.class);
    private static final Set<String> MAPPED_VALUES = Set.of("payment_retry_needed", "inventory_reallocation_needed", "address_confirmation_needed",
        "carrier_lost_in_transit", "node_capacity_backlog", "awaiting_customer_response");

    private final ResourceAction resourceAction;
    private final RepromiseAction repromiseAction;
    private final ReauthorizeAction reauthorizeAction;
    private final ContactCustomerAction contactCustomerAction;
    private final GuardrailService guardrails;
    private final AutonomousActionRepository actionRepo;
    private final DecisionEscalationRepository escalations;
    private final HumanTaskService tasks;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;

    public AutonomousActionExecutor(ResourceAction resourceAction, RepromiseAction repromiseAction, ReauthorizeAction reauthorizeAction,
                                    ContactCustomerAction contactCustomerAction, GuardrailService guardrails, AutonomousActionRepository actionRepo,
                                    DecisionEscalationRepository escalations, HumanTaskService tasks, EventStore events, TimeSource time, Metrics metrics) {
        this.resourceAction = resourceAction;
        this.repromiseAction = repromiseAction;
        this.reauthorizeAction = reauthorizeAction;
        this.contactCustomerAction = contactCustomerAction;
        this.guardrails = guardrails;
        this.actionRepo = actionRepo;
        this.escalations = escalations;
        this.tasks = tasks;
        this.events = events;
        this.time = time;
        this.metrics = metrics;
    }

    /** Diagnosis value -> action type, exactly per the Agentic Control Tower spec. */
    public static Optional<AutonomousActionType> mapDiagnosisToAction(String value) {
        return switch (value) {
            case "payment_retry_needed" -> Optional.of(AutonomousActionType.RETRY_PAYMENT);
            case "inventory_reallocation_needed" -> Optional.of(AutonomousActionType.RE_SOURCE);
            case "address_confirmation_needed", "awaiting_customer_response" -> Optional.of(AutonomousActionType.NOTIFY_CUSTOMER);
            case "carrier_lost_in_transit", "node_capacity_backlog" -> Optional.of(AutonomousActionType.RE_PROMISE);
            default -> Optional.empty();
        };
    }

    @Transactional
    public Anomaly resolve(Order order, String anomalyType, DecisionResult result) {
        Instant now = time.now();
        String value = result.value() == null ? null : String.valueOf(result.value());

        if (result.requiresHuman()) {
            String taskId = result.escalationId() == null ? null : escalations.find(result.escalationId()).map(e -> e.taskId()).orElse(null);
            String queue = HumanTaskEscalationHandler.queueFor(Tier.T2, AnomalyScanner.DECISION_NAME);
            String reason = "decision " + result.band().name().toLowerCase(Locale.ROOT) + " band (confidence " + round(result.confidence()) + ")"
                + (result.reasonCodes().isEmpty() ? "" : "; " + String.join(", ", result.reasonCodes()));
            return anomaly(order, anomalyType, result, Anomaly.OUTCOME_ESCALATED, null, taskId, queue, reason, now);
        }

        if (!result.acts() || value == null || !MAPPED_VALUES.contains(value)) {
            return escalateManually(order, anomalyType, result, value == null ? "no diagnosis value" : "diagnosis '" + value + "' needs a person, not an automated action", now);
        }

        AutonomousActionType type = mapDiagnosisToAction(value).orElseThrow();
        BigDecimal usdImpact = estimateImpactUsd(type, order);
        GuardrailDecision gr = guardrails.evaluate(type, result.confidence(), usdImpact);
        if (!gr.allowed()) return escalateManually(order, anomalyType, result, "guardrail blocked autonomous execution: " + gr.reason(), now);
        return execute(order, anomalyType, result, type, value, now);
    }

    private Anomaly execute(Order order, String anomalyType, DecisionResult result, AutonomousActionType type, String value, Instant now) {
        GuardrailConfig cfg = guardrails.load();
        Map<String, Object> snapshot = cfg.snapshot(actionRepo.countExecutedToday());
        snapshot.put("estimatedImpactUsd", estimateImpactUsd(type, order));
        String nodeId = ActionSupport.activeGroups(order, null).stream().findFirst().map(g -> g.nodeId()).orElse(null);
        ExceptionRecord synthetic = syntheticExceptionRecord(order, nodeId, value, result, now);
        PlaybookContext ctx = new PlaybookContext(synthetic, Optional.of(order), result, Map.of(), Actor.decision(result.recordId()));

        PlaybookStepResult stepResult = switch (type) {
            case RE_SOURCE -> resourceAction.run(ctx, Map.of());
            case RE_PROMISE -> repromiseAction.run(ctx, Map.of("template", "delay_notice"));
            case RETRY_PAYMENT -> reauthorizeAction.run(ctx, Map.of());
            case NOTIFY_CUSTOMER -> contactCustomerAction.run(ctx, Map.of("template", templateFor(value)));
            default -> PlaybookStepResult.skipped("no handler for " + type.code());
        };

        boolean executed = !stepResult.failed() && !"skipped".equals(stepResult.status());
        String actionId = Ids.prefixed("aut");
        AutonomousAction action = new AutonomousAction(actionId, TenantContext.require(), order.id(), anomalyType, result.recordId(), value,
            result.confidence(), type.code(), snapshot, executed ? AutonomousAction.OUTCOME_EXECUTED : AutonomousAction.OUTCOME_FAILED,
            executed && type.undoable(), stepResult.detail(), stepResult.data(), now, null, null, null);
        actionRepo.insert(action);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionId", actionId);
        payload.put("orderId", order.id());
        payload.put("actionTaken", type.code());
        payload.put("diagnosisValue", value);
        payload.put("confidence", result.confidence());
        payload.put("decisionRecordId", result.recordId());
        payload.put("detail", stepResult.detail());
        events.append("order", order.id(), new NewEvent(executed ? "autonomy.action_executed" : "autonomy.action_failed", payload, Actor.decision(result.recordId())));
        metrics.count(executed ? "oms.orchestration.autonomy.actions_executed" : "oms.orchestration.autonomy.actions_failed", "actionType", type.code());

        if (executed) {
            return anomaly(order, anomalyType, result, Anomaly.OUTCOME_EXECUTED, actionId, null, null, null, now);
        }
        // The guardrails allowed it, but the underlying service found nothing actionable (e.g. a payment that is
        // outright declined, not merely expiring) or the call failed outright: escalate honestly rather than
        // pretending the order is handled.
        String taskId = raiseHumanTask(order, anomalyType, result, "autonomous " + type.code() + " attempted but did not complete: " + stepResult.detail(), actionId, now);
        String queue = HumanTaskEscalationHandler.queueFor(Tier.T2, AnomalyScanner.DECISION_NAME);
        return anomaly(order, anomalyType, result, Anomaly.OUTCOME_ESCALATED, actionId, taskId, queue, "autonomous " + type.code() + " did not complete: " + stepResult.detail(), now);
    }

    private Anomaly escalateManually(Order order, String anomalyType, DecisionResult result, String reason, Instant now) {
        String taskId = raiseHumanTask(order, anomalyType, result, reason, null, now);
        String queue = HumanTaskEscalationHandler.queueFor(Tier.T2, AnomalyScanner.DECISION_NAME);
        return anomaly(order, anomalyType, result, Anomaly.OUTCOME_ESCALATED, null, taskId, queue, reason, now);
    }

    private String raiseHumanTask(Order order, String anomalyType, DecisionResult result, String reason, String actionId, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("anomalyType", anomalyType);
        payload.put("decisionRecordId", result.recordId());
        payload.put("diagnosisValue", result.value());
        payload.put("confidence", result.confidence());
        payload.put("band", result.band() == null ? null : result.band().name());
        payload.put("reasonCodes", result.reasonCodes());
        payload.put("blockedReason", reason);
        if (actionId != null) payload.put("attemptedActionId", actionId);
        String queue = HumanTaskEscalationHandler.queueFor(Tier.T2, AnomalyScanner.DECISION_NAME);
        HumanTask task = tasks.create(new TaskRequest("autonomy_escalation", queue, "order", order.id(),
            "Stuck order " + order.id() + " needs a human: " + reason, payload, null, Set.of("ops"), 4, null));
        log.info("Autonomy escalated order {} to {} as task {}: {}", order.id(), queue, task.id(), reason);
        return task.id();
    }

    private static Anomaly anomaly(Order order, String anomalyType, DecisionResult result, String outcome, String actionId, String taskId, String queue, String reason, Instant now) {
        return new Anomaly(Ids.prefixed("anom"), TenantContext.require(), order.id(), anomalyType, result.recordId(),
            result.value() == null ? null : String.valueOf(result.value()), result.confidence(), result.band() == null ? null : result.band().name(),
            result.reasonCodes(), outcome, actionId, taskId, queue, reason, now);
    }

    private static ExceptionRecord syntheticExceptionRecord(Order order, String nodeId, String value, DecisionResult result, Instant now) {
        // Transient only — never persisted to the exceptions table. Built solely to satisfy PlaybookContext so the
        // existing orchestration actions (ResourceAction, RepromiseAction, ReauthorizeAction, ContactCustomerAction)
        // can be reused unmodified for autonomous execution.
        return new ExceptionRecord(Ids.prefixed("aut"), order.id(), null, nodeId, "autonomy_" + value, "medium", "ops_exceptions", "OPEN",
            "autonomy", value, "Autonomous diagnosis: " + value, Map.of(), result.recordId(), result.confidence(), null, List.of(), null,
            result.recordId(), null, null, null, null, null, null, 1, now, null, now, now);
    }

    static String templateFor(String diagnosisValue) {
        return "address_confirmation_needed".equals(diagnosisValue) ? "address_confirm" : "generic";
    }

    static BigDecimal estimateImpactUsd(AutonomousActionType type, Order order) {
        return switch (type) {
            case NOTIFY_CUSTOMER, RELEASE_HOLD -> BigDecimal.ZERO;
            default -> order.merchandiseTotal().amount();
        };
    }

    static String round(double d) { return String.format(Locale.ROOT, "%.2f", d); }
}
