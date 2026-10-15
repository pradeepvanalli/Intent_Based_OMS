package ai.intentoms.agent.mcp;

import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.platform.json.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** {@code inventory_check}: channel-facing network availability per SKU (FR-AGT-01, FR-INV-03). */
@Component
public class InventoryCheckTool implements McpTool {
    private final InventoryService inventory;

    public InventoryCheckTool(InventoryService inventory) { this.inventory = inventory; }

    @Override public String name() { return "inventory_check"; }
    @Override public String description() { return "Network availability (units available to promise across all nodes, after the channel buffer) for up to 50 SKUs."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> arr = Schemas.array(Schemas.string("SKU"), "SKUs to check", 1);
        arr.put("maxItems", 50);
        return Schemas.object(Schemas.props("skus", arr), List.of("skus"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        List<String> skus = Json.convert(args.get("skus"), new TypeReference<List<String>>() {});
        return Map.of("availability", inventory.networkAtp(skus));
    }
}
