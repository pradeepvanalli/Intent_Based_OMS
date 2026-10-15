package ai.intentoms.agent.mcp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.modification.Feasibility;
import ai.intentoms.order.modification.Modification;
import ai.intentoms.order.modification.OrderModificationService;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.json.Json;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code order_modify}: checks feasibility then applies a change (FR-AGT-01, FR-CS-02). Changes that need approval, or
 * agents with {@code order_modify} in {@code confirmActions}, get a pending confirmation task instead (FR-AGT-04).
 */
@Component
public class OrderModifyTool implements McpTool {
    private final OrderModificationService modifications;
    private final OrderService orders;
    private final ConfirmationTasks confirmations;

    public OrderModifyTool(OrderModificationService modifications, OrderService orders, ConfirmationTasks confirmations) {
        this.modifications = modifications;
        this.orders = orders;
        this.confirmations = confirmations;
    }

    @Override public String name() { return "order_modify"; }
    @Override public String description() { return "Change an order: ChangeAddress {address}, ChangeQty {lineId, qty}, CancelLine {lineId}, ChangeShipMethod {shipMethod}, ChangeItem {lineId, newSku}. Feasibility is checked first; infeasible changes return isError with the reasons."; }
    @Override public boolean readOnly() { return false; }
    @Override public boolean destructive() { return true; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> modification = Schemas.object(Schemas.props(
            "type", Schemas.string("Change type", List.of("ChangeAddress", "ChangeQty", "CancelLine", "ChangeShipMethod", "ChangeItem")),
            "lineId", Schemas.string(null), "qty", Schemas.integer(null, 0, null), "newSku", Schemas.string(null), "shipMethod", Schemas.string(null),
            "address", Schemas.anyObject("Address {type: ship_to, name, line1, line2, city, state, zip, country}")), List.of("type"), true);
        return Schemas.object(Schemas.props("orderId", Schemas.string("OMS order id"), "modification", modification, "reason", Schemas.string("Why the buyer wants the change")), List.of("orderId", "modification"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        String orderId = orderIdOf(args);
        Modification m = Json.convert(args.get("modification"), Modification.class);
        if (m instanceof Modification.CancelOrder) throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "Use order_cancel to cancel a whole order");
        Feasibility f = modifications.check(orderId, m);
        Map<String, Object> feasibility = feasibility(f);
        if (!f.allowed()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "modification_not_allowed");
            out.put("feasibility", feasibility);
            return new ToolError(out);
        }
        String reason = args.get("reason") == null ? "requested by agent " + ctx.callerId() : args.get("reason").toString();
        if (ctx.requiresConfirmation(name()) || f.requiresApproval()) return confirmations.park(ctx, name(), orderId, args, Map.of("feasibility", feasibility, "reason", reason));
        Order updated = modifications.apply(orderId, m, reason, ctx.actor());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "applied");
        out.put("feasibility", feasibility);
        out.put("order", CustomerSafeOrderView.order(orders.view(updated.id())));
        return out;
    }

    static Map<String, Object> feasibility(Feasibility f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowed", f.allowed());
        m.put("reasons", f.reasons());
        m.put("priceDelta", CustomerSafeOrderView.money(f.costDelta()));
        m.put("requiresApproval", f.requiresApproval());
        m.put("carrierIntercept", f.carrierIntercept());
        if (f.newPromise() != null) m.put("newPromise", Map.of("date", f.newPromise().date(), "onTimeProbability", f.newPromise().onTimeProbability()));
        m.put("affectedLines", f.affectedLines());
        return m;
    }

    /** Marker result the executor turns into {@code isError: true} without a JSON-RPC error. */
    public record ToolError(Map<String, Object> body) {}
}
