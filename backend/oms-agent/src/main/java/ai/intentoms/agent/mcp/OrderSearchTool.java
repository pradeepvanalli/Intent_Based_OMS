package ai.intentoms.agent.mcp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.model.OrderSummaryView;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.jdbc.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code order_search}: customer-safe summaries; tier-0 agents only see orders they placed (FR-AGT-01, FR-AGT-03). */
@Component
public class OrderSearchTool implements McpTool {
    private final OrderService orders;

    public OrderSearchTool(OrderService orders) { this.orders = orders; }

    @Override public String name() { return "order_search"; }
    @Override public String description() { return "Search orders by customer reference, status and placed-at range. Returns customer-safe summaries."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props(
            "customerRef", Schemas.string("Customer reference"),
            "status", Schemas.string("Header status (created, sourcing, released, shipped, delivered, cancelled, ...)"),
            "placedFrom", Schemas.string("ISO-8601 instant, inclusive"),
            "placedTo", Schemas.string("ISO-8601 instant, exclusive"),
            "limit", Schemas.integer("Max rows (default 20, max 100)", 1, 100)), List.of());
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 20;
        String status = str(args.get("status"));
        OrderQuery q = new OrderQuery(status == null ? null : List.of(status), null, null, str(args.get("customerRef")), null, instant(args.get("placedFrom")), instant(args.get("placedTo")),
            null, ctx.ownershipRestricted() ? Boolean.TRUE : null, null, 0, limit);
        Page<OrderSummaryView> page = orders.searchView(q, null);
        List<Map<String, Object>> items = page.items().stream()
            .filter(s -> !ctx.ownershipRestricted() || ctx.callerId().equals(s.agentId()))
            .map(CustomerSafeOrderView::summary).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", ctx.ownershipRestricted() ? items.size() : page.total());
        out.put("limit", limit);
        return out;
    }

    private static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString(); }

    private static Instant instant(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return Instant.parse(o.toString()); } catch (Exception e) { throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "Not an ISO-8601 instant: " + o); }
    }
}
