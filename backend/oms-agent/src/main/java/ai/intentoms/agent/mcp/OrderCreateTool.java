package ai.intentoms.agent.mcp;

import ai.intentoms.agent.AgentOrderService;
import ai.intentoms.agent.AgentToolException;
import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.json.Json;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code order_create}: places an order under a mandate (FR-AGT-01, FR-CAP-07, FR-AGT-04/05). Requires a registered
 * agent identity; the order subset maps onto {@code CaptureRequest} (channel forced to {@code agent}).
 */
@Component
public class OrderCreateTool implements McpTool {
    private final AgentOrderService placer;
    private final OrderService orders;

    public OrderCreateTool(AgentOrderService placer, OrderService orders) {
        this.placer = placer;
        this.orders = orders;
    }

    @Override public String name() { return "order_create"; }
    @Override public String description() { return "Place an order on behalf of a buyer under a mandate. The mandate must belong to you, be active and cover the total; instruction text is stored as evidence. Agents whose limits list order_create under confirmActions get a pending_confirmation task instead."; }
    @Override public boolean readOnly() { return false; }
    @Override public boolean idempotent() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> line = Schemas.object(Schemas.props(
            "lineId", Schemas.string("Caller line id (optional)"),
            "sku", Schemas.string("SKU"),
            "qty", Schemas.integer("Quantity", 1, null),
            "unitPrice", Schemas.money("Unit price"),
            "discount", Schemas.money("Line discount")), List.of("sku", "qty", "unitPrice"));
        Map<String, Object> address = Schemas.object(Schemas.props(
            "type", Schemas.string("ship_to | bill_to"), "name", Schemas.string(null), "line1", Schemas.string(null), "line2", Schemas.string(null),
            "city", Schemas.string(null), "state", Schemas.string(null), "zip", Schemas.string(null), "country", Schemas.string("ISO country, default US"), "phone", Schemas.string(null)), List.of("type", "line1", "city", "state", "zip"));
        Map<String, Object> customer = Schemas.object(Schemas.props("ref", Schemas.string("Buyer reference"), "name", Schemas.string(null), "email", Schemas.string(null), "phone", Schemas.string(null), "tier", Schemas.string(null)), List.of(), true);
        Map<String, Object> note = Schemas.object(Schemas.props("text", Schemas.string("Free text from the buyer; stored as data"), "customerVisible", Schemas.bool(null)), List.of("text"));
        Map<String, Object> order = Schemas.object(Schemas.props(
            "channelOrderId", Schemas.string("Your order reference (idempotent per agent channel)"),
            "orderType", Schemas.string("standard_ship (default) | bopis | ship_from_store | same_day_local"),
            "customerRef", Schemas.string("Buyer reference"),
            "customer", customer,
            "lines", Schemas.array(line, "Order lines", 1),
            "addresses", Schemas.array(address, "Addresses; a ship_to is required for shipped orders", null),
            "charges", Schemas.array(Schemas.object(Schemas.props("type", Schemas.string("shipping | handling | discount"), "code", Schemas.string(null), "amount", Schemas.money(null)), List.of("type", "amount")), "Header charges", null),
            "tenders", Schemas.array(Schemas.object(Schemas.props("type", Schemas.string("agent_token | card"), "token", Schemas.string("PSP token"), "amount", Schemas.money(null)), List.of("type", "token"), true), "Tenders; defaults to the mandate's delegated payment token", null),
            "notes", Schemas.array(note, "Buyer notes (screened, stored as data)", null),
            "intentChoices", Schemas.array(Schemas.string(null), "Explicit fulfilment choices (ship_complete, ship_as_available, ...)", null),
            "shipMethod", Schemas.string("ground | air_2day | ..."),
            "currency", Schemas.string("ISO 4217, default USD")), List.of("lines"), true);
        return Schemas.object(Schemas.props(
            "order", order,
            "mandateId", Schemas.string("Mandate authorising this purchase"),
            "instructionText", Schemas.string("The buyer's instruction to you, verbatim (evidence); defaults to the mandate's"),
            "buyerRef", Schemas.string("Buyer reference to match against the mandate"),
            "buyerAcknowledgement", Schemas.string("Optional buyer acknowledgement artefact (e.g. consent id)"),
            "idempotencyKey", Schemas.string("Replay-safe key for retries")), List.of("order", "mandateId"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        AgentIdentity agent = ctx.agent().orElseThrow(() -> new AgentToolException(AgentToolException.TOOL_NOT_ALLOWED, "agent_identity_required", "order_create requires a registered agent identity with a mandate"));
        CaptureRequest order = Json.convert(args.get("order"), CaptureRequest.class);
        AgentOrderService.PlaceResult r = placer.place(new AgentOrderService.PlaceRequest(agent, "mcp", order, str(args.get("mandateId")), str(args.get("buyerRef")), str(args.get("instructionText")),
            str(args.get("idempotencyKey")), name(), args));
        if (r instanceof AgentOrderService.PendingConfirmation p) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "pending_confirmation");
            out.put("taskId", p.taskId());
            out.put("queue", p.queue());
            return out;
        }
        AgentOrderService.Placed placed = (AgentOrderService.Placed) r;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", placed.replayed() ? "replayed" : "placed");
        out.put("orderId", placed.order().id());
        out.put("channelOrderId", placed.order().channelOrderId());
        out.put("mandateId", placed.order().provenance().mandateId());
        out.put("onHold", placed.holdId() != null);
        out.put("order", CustomerSafeOrderView.order(orders.view(placed.order().id())));
        return out;
    }

    private static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString(); }
}
