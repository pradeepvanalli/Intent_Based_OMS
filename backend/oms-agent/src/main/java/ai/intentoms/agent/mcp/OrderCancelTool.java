package ai.intentoms.agent.mcp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.modification.Feasibility;
import ai.intentoms.order.modification.Modification;
import ai.intentoms.order.modification.OrderModificationService;
import ai.intentoms.order.service.OrderService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code order_cancel}: cancels every cancellable unit of an order (FR-AGT-01); honours {@code confirmActions} (FR-AGT-04). */
@Component
public class OrderCancelTool implements McpTool {
    private final OrderModificationService modifications;
    private final OrderService orders;
    private final ConfirmationTasks confirmations;

    public OrderCancelTool(OrderModificationService modifications, OrderService orders, ConfirmationTasks confirmations) {
        this.modifications = modifications;
        this.orders = orders;
        this.confirmations = confirmations;
    }

    @Override public String name() { return "order_cancel"; }
    @Override public String description() { return "Cancel a whole order before it ships. Units already shipped cannot be cancelled (use return_create after delivery)."; }
    @Override public boolean readOnly() { return false; }
    @Override public boolean destructive() { return true; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props("orderId", Schemas.string("OMS order id"), "reason", Schemas.string("Why the buyer cancels")), List.of("orderId", "reason"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        String orderId = orderIdOf(args);
        Modification m = new Modification.CancelOrder();
        Feasibility f = modifications.check(orderId, m);
        Map<String, Object> feasibility = OrderModifyTool.feasibility(f);
        if (!f.allowed()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "cancel_not_allowed");
            out.put("feasibility", feasibility);
            return new OrderModifyTool.ToolError(out);
        }
        String reason = args.get("reason").toString();
        if (ctx.requiresConfirmation(name()) || f.requiresApproval()) return confirmations.park(ctx, name(), orderId, args, Map.of("feasibility", feasibility, "reason", reason));
        Order updated = modifications.apply(orderId, m, reason, ctx.actor());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "cancelled");
        out.put("feasibility", feasibility);
        out.put("order", CustomerSafeOrderView.order(orders.view(updated.id())));
        return out;
    }
}
