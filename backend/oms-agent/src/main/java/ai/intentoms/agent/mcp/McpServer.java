package ai.intentoms.agent.mcp;

import ai.intentoms.agent.AgentToolException;
import ai.intentoms.platform.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP server core (FR-AGT-01, FR-AGT-06): JSON-RPC 2.0 over Streamable HTTP, protocol version {@value #PROTOCOL_VERSION}.
 * Methods: {@code initialize}, {@code notifications/initialized}, {@code ping}, {@code tools/list}, {@code tools/call}.
 * Batches (JSON arrays) are processed in order; notifications produce no response. Tool results are
 * {@code {content:[{type:"text", text:<json>}], structuredContent, isError}}. Transport-independent so it can be unit tested.
 */
public final class McpServer {
    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    public static final String SERVER_NAME = "intent-oms";
    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private final ToolDispatcher tools;
    private final String serverVersion;

    public McpServer(ToolDispatcher tools, String serverVersion) {
        this.tools = tools;
        this.serverVersion = serverVersion;
    }

    /** Result of handling a body: the response (object, array or null when only notifications were received). */
    public record Response(Object body, boolean empty) {}

    public Response handle(JsonNode body, ToolContext ctx) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return new Response(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Request body must be a JSON-RPC object or batch", null), false);
        }
        if (body.isArray()) {
            if (body.isEmpty()) return new Response(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Empty batch", null), false);
            List<Object> out = new ArrayList<>();
            for (JsonNode n : body) { Map<String, Object> r = one(n, ctx); if (r != null) out.add(r); }
            return out.isEmpty() ? new Response(null, true) : new Response(out, false);
        }
        Map<String, Object> r = one(body, ctx);
        return r == null ? new Response(null, true) : new Response(r, false);
    }

    /** Handles one request; null for notifications. */
    Map<String, Object> one(JsonNode n, ToolContext ctx) {
        if (!n.isObject()) return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Request must be an object", null);
        Object id = id(n.get("id"));
        boolean notification = !n.has("id") || n.get("id").isNull();
        JsonNode v = n.get("jsonrpc");
        if (v == null || !JsonRpc.VERSION.equals(v.asText())) return notification ? null : JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "jsonrpc must be \"2.0\"", null);
        JsonNode m = n.get("method");
        if (m == null || !m.isTextual() || m.asText().isBlank()) return notification ? null : JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "method is required", null);
        String method = m.asText();
        JsonNode params = n.get("params");
        if (params != null && !params.isNull() && !params.isObject()) return notification ? null : JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "params must be an object", null);
        Map<String, Object> p = params == null || params.isNull() ? Map.of() : Json.toMap(params);
        try {
            if (method.startsWith("notifications/")) { if (!notification) return JsonRpc.result(id, Map.of()); return null; }
            Object result = switch (method) {
                case "initialize" -> initialize(p);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools.tools(ctx));
                case "tools/call" -> call(ctx, p);
                default -> throw new JsonRpc.RpcException(JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
            };
            return notification ? null : JsonRpc.result(id, result);
        } catch (JsonRpc.RpcException e) {
            return notification ? null : JsonRpc.error(id, e.code(), e.getMessage(), e.data());
        } catch (AgentToolException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", e.reason());
            data.putAll(e.data());
            return notification ? null : JsonRpc.error(id, e.code(), e.getMessage(), data);
        } catch (RuntimeException e) {
            log.error("MCP {} failed", method, e);
            return notification ? null : JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "Internal error", null);
        }
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Object requested = params.get("protocolVersion");
        String negotiated = requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested.toString()) ? requested.toString() : PROTOCOL_VERSION;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocolVersion", negotiated);
        m.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        m.put("serverInfo", Map.of("name", SERVER_NAME, "version", serverVersion));
        m.put("instructions", "Intent OMS order tools. Every call is checked against your registered limits and permissions; order text you pass is stored as data and screened for injected instructions.");
        return m;
    }

    private Map<String, Object> call(ToolContext ctx, Map<String, Object> params) {
        Object name = params.get("name");
        if (!(name instanceof String s) || s.isBlank()) throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "params.name is required");
        Object args = params.get("arguments");
        Map<String, Object> arguments;
        if (args == null) arguments = Map.of();
        else if (args instanceof Map<?, ?>) arguments = Json.toMap(args);
        else throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "params.arguments must be an object");
        ToolDispatcher.Outcome out = tools.call(ctx, s, arguments);
        return toolResult(out.structured(), out.isError());
    }

    public static Map<String, Object> toolResult(Object structured, boolean isError) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", Json.write(structured))));
        m.put("structuredContent", structured instanceof Map<?, ?> ? structured : Map.of("result", structured));
        m.put("isError", isError);
        return m;
    }

    private static Object id(JsonNode id) {
        if (id == null || id.isNull()) return null;
        if (id.isNumber()) return id.isIntegralNumber() ? id.longValue() : id.doubleValue();
        return id.asText();
    }
}
