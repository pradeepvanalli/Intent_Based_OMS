package ai.intentoms.agent.mcp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** {@code order_get}: customer-safe order view by OMS id or channel reference (FR-AGT-01, FR-AGT-03 ownership for tier 0). */
@Component
public class OrderGetTool implements McpTool {
    private final OrderService orders;

    public OrderGetTool(OrderService orders) { this.orders = orders; }

    @Override public String name() { return "order_get"; }
    @Override public String description() { return "Get one order as the buyer may see it: status, lines with quantity statuses, promise dates, shipments with tracking, returns and totals. Identify it by orderId or by channel + channelOrderId."; }
    @Override public boolean readOnly() { return true; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props(
            "orderId", Schemas.string("OMS order id"),
            "channel", Schemas.string("Sales channel of channelOrderId (web, agent, shopify, ...)"),
            "channelOrderId", Schemas.string("The channel's order number")), List.of());
    }

    @Override
    public String orderIdOf(Map<String, Object> args) {
        Object id = args.get("orderId");
        if (id != null && !id.toString().isBlank()) return id.toString();
        Object ch = args.get("channel"); Object ref = args.get("channelOrderId");
        if (ch == null || ref == null) throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "orderId or channel + channelOrderId is required");
        return orders.byChannelRef(ch.toString(), ref.toString()).orElseThrow(() -> new NotFoundException("No order " + ref + " on channel " + ch)).id();
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) { return CustomerSafeOrderView.order(orders.view(orderIdOf(args))); }
}
