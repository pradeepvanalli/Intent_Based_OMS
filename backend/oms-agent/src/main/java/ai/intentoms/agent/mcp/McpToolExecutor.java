package ai.intentoms.agent.mcp;

import ai.intentoms.agent.AgentToolException;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.agent.registry.ToolPermissions;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.GuardrailViolationException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Guarded tool execution (FR-AGT-01, FR-AGT-03, FR-AGT-04, FR-AGT-06). For every call: the tool must exist (-32602), a
 * registered agent's {@code allowedActions} must include it (-32003 {@code tool_not_allowed}), the principal must hold the
 * tool's permission (-32003 {@code permission_denied}; people and agents share the model), the arguments must validate
 * against the tool's JSON Schema (-32602), and a tier-0 agent may only touch orders it placed (-32004 {@code not_owner}).
 * Each call is recorded in {@code agent_activity} and metric {@code oms.agent.tool{tool,outcome}}. Domain failures from
 * downstream services (validation, conflict, not found, guardrail) become tool results with {@code isError=true}; refusals
 * and limit breaches become JSON-RPC errors.
 */
@Component
public class McpToolExecutor implements ToolDispatcher {
    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();
    private final OrderService orders;
    private final AgentRegistry registry;
    private final Metrics metrics;

    public McpToolExecutor(List<McpTool> toolBeans, OrderService orders, AgentRegistry registry, Metrics metrics) {
        for (McpTool t : toolBeans) tools.put(t.name(), t);
        this.orders = orders;
        this.registry = registry;
        this.metrics = metrics;
    }

    public Set<String> toolNames() { return tools.keySet(); }

    @Override
    public List<Map<String, Object>> tools(ToolContext ctx) {
        return allowedTools(ctx).stream().map(McpTool::descriptor).toList();
    }

    /** The tools this caller may actually see: filtered by the agent's own {@code allowedActions} (when it is an
     * agent) and by the principal's own permissions. Shared by {@link #tools(ToolContext)} ({@code tools/list}) and
     * the unknown-tool error below (D5-6/MCP-3 (a)) so neither leaks a name the caller cannot invoke. */
    private List<McpTool> allowedTools(ToolContext ctx) {
        return tools.values().stream()
            .filter(t -> ctx.agent().map(a -> a.limits().allows(t.name())).orElse(true))
            .filter(t -> ctx.principal().has(ToolPermissions.permissionFor(t.name())))
            .toList();
    }

    @Override
    public Outcome call(ToolContext ctx, String toolName, Map<String, Object> rawArgs) {
        String name = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> args = rawArgs == null ? Map.of() : rawArgs;
        McpTool tool = tools.get(name);
        if (tool == null) {
            // MCP-3 (a): name only the tools this caller is actually allowed to see, not the whole registry —
            // tools/list (tools(ToolContext)) already filters this way; an unknown-tool error must not leak more.
            List<String> visible = allowedTools(ctx).stream().map(McpTool::name).toList();
            throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "Unknown tool: " + toolName, Map.of("tools", visible));
        }
        long start = System.nanoTime();
        String orderId = null;
        try {
            if (ctx.agent().isPresent() && !ctx.agent().get().limits().allows(name)) {
                throw new AgentToolException(AgentToolException.TOOL_NOT_ALLOWED, "tool_not_allowed", "Agent " + ctx.callerId() + " is not allowed to call " + name,
                    Map.of("allowedActions", ctx.agent().get().limits().allowedActions()));
            }
            String permission = ToolPermissions.permissionFor(name);
            if (permission == null || !ctx.principal().has(permission)) {
                throw new AgentToolException(AgentToolException.TOOL_NOT_ALLOWED, "permission_denied", "Missing permission " + permission + " for " + name, Map.of("permission", String.valueOf(permission)));
            }
            validate(tool, args);
            orderId = tool.orderIdOf(args);
            if (orderId != null && ctx.ownershipRestricted()) assertOwner(ctx, orderId);
            Object result = tool.call(ctx, args);
            if (result instanceof OrderModifyTool.ToolError te) {
                record(ctx, name, "error", orderId, start, Map.of("error", String.valueOf(te.body().get("error"))));
                return new Outcome(te.body(), true);
            }
            record(ctx, name, "ok", orderId, start, Map.of());
            return new Outcome(result, false);
        } catch (AgentToolException e) {
            record(ctx, name, "denied", orderId, start, Map.of("reason", e.reason()));
            throw e;
        } catch (JsonRpc.RpcException e) {
            record(ctx, name, "invalid", orderId, start, Map.of("code", e.code()));
            throw e;
        } catch (ForbiddenException e) {
            record(ctx, name, "denied", orderId, start, Map.of("reason", "forbidden"));
            throw new AgentToolException(AgentToolException.FORBIDDEN, "forbidden", e.getMessage());
        } catch (OmsException e) {
            record(ctx, name, "error", orderId, start, Map.of("code", e.code()));
            return new Outcome(errorBody(e), true);
        } catch (IllegalArgumentException e) {
            record(ctx, name, "invalid", orderId, start, Map.of());
            throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (RuntimeException e) {
            record(ctx, name, "error", orderId, start, Map.of("exception", e.getClass().getSimpleName()));
            log.error("Tool {} failed for {}", name, ctx.callerId(), e);
            throw new JsonRpc.RpcException(JsonRpc.INTERNAL_ERROR, "Internal error executing " + name);
        }
    }

    private void validate(McpTool tool, Map<String, Object> args) {
        JsonSchema schema = schemas.computeIfAbsent(tool.name(), k -> FACTORY.getSchema(Json.toTree(tool.inputSchema())));
        Set<ValidationMessage> errors = schema.validate(Json.toTree(args));
        if (!errors.isEmpty()) {
            List<String> msgs = errors.stream().map(ValidationMessage::getMessage).sorted().toList();
            throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "Invalid arguments for " + tool.name() + ": " + String.join("; ", msgs), Map.of("errors", msgs));
        }
    }

    private void assertOwner(ToolContext ctx, String orderId) {
        Order o;
        try { o = orders.get(orderId); }
        catch (NotFoundException e) { throw new AgentToolException(AgentToolException.NOT_OWNER, "not_owner", "Order " + orderId + " is not accessible to agent " + ctx.callerId()); }
        if (o.provenance() == null || !ctx.callerId().equals(o.provenance().agentId())) {
            throw new AgentToolException(AgentToolException.NOT_OWNER, "not_owner", "Order " + orderId + " was not placed by agent " + ctx.callerId() + " (trust tier 0 agents may only read their own orders)");
        }
    }

    private void record(ToolContext ctx, String tool, String outcome, String orderId, long startNanos, Map<String, Object> details) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        metrics.count("oms.agent.tool", "tool", tool, "outcome", outcome);
        metrics.record("oms.agent.tool.latency", java.time.Duration.ofMillis(ms), "tool", tool);
        if (ctx.isAgent()) {
            try { registry.recordActivity(ctx.callerId(), tool, outcome, orderId, ms, details); }
            catch (RuntimeException e) { log.warn("Could not record activity for {}: {}", ctx.callerId(), e.toString()); }
        }
    }

    static Map<String, Object> errorBody(OmsException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.code());
        m.put("status", e.status().value());
        m.put("message", e.getMessage());
        if (!e.details().isEmpty()) m.put("details", e.details().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)));
        if (e instanceof ConflictException) m.put("kind", "conflict");
        else if (e instanceof ValidationException) m.put("kind", "validation");
        else if (e instanceof NotFoundException) m.put("kind", "not_found");
        else if (e instanceof GuardrailViolationException) m.put("kind", "guardrail");
        return m;
    }
}
