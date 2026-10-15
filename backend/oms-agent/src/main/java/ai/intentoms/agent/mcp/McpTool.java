package ai.intentoms.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One MCP tool (FR-AGT-01). Implementations are Spring beans; the executor enforces per-agent limits, permissions,
 * schema validation and tier-0 ownership before {@link #call} runs. {@link #orderIdOf} names the order an invocation
 * touches so ownership can be checked generically.
 */
public interface McpTool {
    String name();
    String description();
    Map<String, Object> inputSchema();
    /** MCP tool annotations: readOnlyHint, destructiveHint, idempotentHint, openWorldHint. */
    boolean readOnly();
    default boolean destructive() { return false; }
    default boolean idempotent() { return readOnly(); }

    /** The order id the call concerns, or null when the tool is not order-scoped. */
    default String orderIdOf(Map<String, Object> args) { Object v = args.get("orderId"); return v == null ? null : v.toString(); }

    /** Executes the tool; returns the structured result (any JSON-serialisable value). */
    Object call(ToolContext ctx, Map<String, Object> args);

    default Map<String, Object> descriptor() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name());
        m.put("description", description());
        m.put("inputSchema", inputSchema());
        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put("title", name().replace('_', ' '));
        ann.put("readOnlyHint", readOnly());
        ann.put("destructiveHint", destructive());
        ann.put("idempotentHint", idempotent());
        ann.put("openWorldHint", false);
        m.put("annotations", ann);
        return m;
    }

    static List<String> required(String... names) { return List.of(names); }
}
