package ai.intentoms.app.glue;

import ai.intentoms.agent.AgentOrderVerifier;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderTypeRegistry;
import ai.intentoms.orchestration.workflow.AgentVerificationHook;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges the agent module (mandate verification, UC-09) into the order workflow's {@code verify_agent} step.
 * The orchestration module deliberately does not depend on the agent module; this adapter lives in the assembled app.
 * <ul>
 *   <li>{@link #verify(Order)} runs the T3 {@code agent_mandate_match} decision. A confident "within mandate" with no
 *       human requirement lets the workflow continue; otherwise the workflow parks on the {@code agent_verification}
 *       hold until a risk reviewer decides.</li>
 *   <li>When the reviewer confirms or rejects (events {@code agent.order_confirmed} / {@code agent.order_rejected}),
 *       the workflow is signalled {@code agent_verified} / {@code agent_rejected} (FR-WF-05, idempotent on event id).</li>
 * </ul>
 */
@Component
public class AgentVerificationBridge implements AgentVerificationHook, EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(AgentVerificationBridge.class);
    private final AgentOrderVerifier verifier;
    private final WorkflowEngine workflows;
    private final OrderTypeRegistry orderTypes;

    public AgentVerificationBridge(AgentOrderVerifier verifier, WorkflowEngine workflows, OrderTypeRegistry orderTypes) {
        this.verifier = verifier;
        this.workflows = workflows;
        this.orderTypes = orderTypes;
    }

    @Override
    public Optional<Boolean> verify(Order order) {
        AgentOrderVerifier.Verification v = verifier.verify(order.id());
        if (!v.requiresHuman() && Boolean.TRUE.equals(v.withinMandate())) return Optional.of(true);
        log.info("Agent order {} parked for human verification (task {}, hold {})", order.id(), v.taskId(), v.holdId());
        return Optional.empty();
    }

    @Override
    public List<String> eventTypePrefixes() { return List.of("agent.order_confirmed", "agent.order_rejected"); }

    @Override
    public void on(Event event) {
        if (!"order".equals(event.aggregateType())) return;
        String orderId = event.aggregateId();
        Object type = event.payload().getOrDefault("orderType", "standard_ship");
        String workflowType;
        try { workflowType = orderTypes.get(String.valueOf(type)).workflowType(); } catch (Exception e) { workflowType = "order.standard_ship"; }
        String signal = event.type().endsWith("confirmed") ? "agent_verified" : "agent_rejected";
        workflows.signal(workflowType, orderId, signal, Map.of("reason", event.payload().getOrDefault("reason", ""), "eventId", event.id()), event.id());
    }
}
