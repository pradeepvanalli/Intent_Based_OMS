package ai.intentoms.agent.mcp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.agent.screen.AgentTextSanitizer;
import ai.intentoms.agent.screen.InjectionScreen;
import ai.intentoms.agent.screen.ScreenResult;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.validation.steps.AgentProvenanceStep;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.returns.model.ReturnChannel;
import ai.intentoms.returns.model.ReturnLineRequest;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.statemachine.HoldService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code return_create}: opens an RMA on channel {@code agent} (FR-AGT-01, FR-RET-01). Reason text is screened before it
 * is stored (GR-08); flagged text keeps the RMA under an {@code agent_verification} hold blocking refund and keep-it.
 */
@Component
public class ReturnCreateTool implements McpTool {
    private final ReturnService returns;
    private final OrderService orders;
    private final InjectionScreen screen;
    private final HoldService holds;
    private final EventStore events;
    private final ConfirmationTasks confirmations;

    public ReturnCreateTool(ReturnService returns, OrderService orders, InjectionScreen screen, HoldService holds, EventStore events, ConfirmationTasks confirmations) {
        this.returns = returns;
        this.orders = orders;
        this.screen = screen;
        this.holds = holds;
        this.events = events;
        this.confirmations = confirmations;
    }

    @Override public String name() { return "return_create"; }
    @Override public String description() { return "Open a return (RMA) for delivered lines of an order. Give lines with quantities and an optional reason code (wrong_size, defective, not_as_described, changed_mind, ...) plus the buyer's reason text."; }
    @Override public boolean readOnly() { return false; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> line = Schemas.object(Schemas.props("lineId", Schemas.string("Order line id"), "qty", Schemas.integer("Units to return", 1, null),
            "reasonCode", Schemas.string("Taxonomy code"), "reasonText", Schemas.string("Buyer's words for this line")), List.of("lineId", "qty"));
        return Schemas.object(Schemas.props("orderId", Schemas.string("OMS order id"), "lines", Schemas.array(line, "Lines to return", 1),
            "reason", Schemas.string("Buyer's reason in their words (stored as data)"), "receiptPresent", Schemas.bool("Whether the buyer has the order confirmation")), List.of("orderId", "lines"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        String orderId = orderIdOf(args);
        Order order = orders.get(orderId);
        List<Map<String, Object>> rawLines = Json.convert(args.get("lines"), new TypeReference<List<Map<String, Object>>>() {});
        List<ReturnLineRequest> lines = new ArrayList<>();
        List<ScreenResult> screens = new ArrayList<>();
        String reason = AgentTextSanitizer.sanitize(args.get("reason") == null ? null : args.get("reason").toString());
        if (reason != null && !reason.isBlank()) screens.add(screen.screen("agent_return_reason", reason, "order", orderId, ai.intentoms.order.model.OrderPii.known(order)));
        for (Map<String, Object> l : rawLines) {
            String text = AgentTextSanitizer.sanitize(l.get("reasonText") == null ? null : l.get("reasonText").toString());
            if (text != null && !text.isBlank()) screens.add(screen.screen("agent_return_reason", text, "order", orderId, ai.intentoms.order.model.OrderPii.known(order)));
            lines.add(new ReturnLineRequest(String.valueOf(l.get("lineId")), ((Number) l.get("qty")).intValue(), l.get("reasonCode") == null ? null : l.get("reasonCode").toString(), text));
        }
        if (ctx.requiresConfirmation(name())) return confirmations.park(ctx, name(), orderId, args, Map.of("injectionFlagged", screens.stream().anyMatch(ScreenResult::flagged)));
        String agentId = ctx.isAgent() ? ctx.callerId() : null;
        String mandateId = order.provenance() == null ? null : order.provenance().mandateId();
        ReturnRma rma = returns.create(new ReturnRequest(orderId, lines, ReturnChannel.AGENT, agentId, mandateId, !Boolean.FALSE.equals(args.get("receiptPresent")), null, reason), ctx.actor());
        String holdId = null;
        if (screens.stream().anyMatch(ScreenResult::flagged)) {
            double max = screens.stream().mapToDouble(ScreenResult::probability).max().orElse(0);
            var hold = holds.place(AgentProvenanceStep.HOLD_TYPE, "return", rma.rmaId(), Set.of("event.refund_issued", "decision.keep_it"), "INJECTION_FLAGGED",
                "Return reason text looks like instructions aimed at the system (p=" + max + ")", null, ctx.actor());
            holdId = hold.id();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rmaId", rma.rmaId());
            payload.put("orderId", orderId);
            payload.put("agentId", ctx.callerId());
            payload.put("holdId", holdId);
            payload.put("screens", screens.stream().map(ScreenResult::toMap).toList());
            events.append(AgentRegistry.AGGREGATE, ctx.callerId(), new NewEvent("agent.injection_flagged", payload, ctx.actor(), null, orderId, Map.of()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "created");
        out.put("rma", rma.rmaId());
        out.put("onHold", holdId != null);
        out.put("return", CustomerSafeOrderView.returnRecord(returns.get(rma.rmaId())));
        return out;
    }
}
