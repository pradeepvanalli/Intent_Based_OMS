package ai.intentoms.agent.mcp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.platform.json.Json;
import ai.intentoms.sourcing.PromiseService;
import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code promise}: delivery promise for a cart (FR-AGT-01, FR-SRC-05). Deterministic, no model call. */
@Component
public class PromiseTool implements McpTool {
    private final PromiseService promises;

    public PromiseTool(PromiseService promises) { this.promises = promises; }

    @Override public String name() { return "promise"; }
    @Override public String description() { return "Delivery promise (date and on-time probability) for a set of SKUs to a US ZIP/state, with alternative ship methods and their price difference."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props(
            "lines", Schemas.array(Schemas.object(Schemas.props("sku", Schemas.string("SKU"), "qty", Schemas.integer("Quantity", 1, null)), List.of("sku", "qty")), "Cart lines", 1),
            "destinationZip", Schemas.string("Destination ZIP (5 digits)"),
            "destinationState", Schemas.string("Destination state code"),
            "shipMethod", Schemas.string("ground | air_2day | air_next_day | same_day (default ground)")), List.of("lines", "destinationZip"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        List<PromiseRequest.PromiseLine> lines = Json.convert(args.get("lines"), new TypeReference<List<PromiseRequest.PromiseLine>>() {});
        Promise p = promises.promise(new PromiseRequest(lines, String.valueOf(args.get("destinationZip")), args.get("destinationState") == null ? null : args.get("destinationState").toString(),
            ctx.isAgent() ? "agent" : "web", args.get("shipMethod") == null ? null : args.get("shipMethod").toString()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", p.date());
        out.put("onTimeProbability", p.onTimeProbability());
        out.put("shipMethod", p.shipMethod());
        out.put("inStock", p.fromNodeId() != null);
        out.put("alternatives", p.alternatives().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shipMethod", a.shipMethod());
            m.put("date", a.date());
            m.put("onTimeProbability", a.onTimeProbability());
            m.put("priceDelta", CustomerSafeOrderView.money(a.costDelta()));
            return m;
        }).toList());
        return out;
    }
}
