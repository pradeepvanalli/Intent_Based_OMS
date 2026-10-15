package ai.intentoms.agent.mcp;

import ai.intentoms.agent.AgentToolException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-AGT-01: JSON-RPC envelope, batches, notifications and error codes, independent of Spring. */
class McpServerTest {
    private final ToolDispatcher dispatcher = new ToolDispatcher() {
        @Override public List<Map<String, Object>> tools(ToolContext ctx) { return List.of(Map.of("name", "order_get", "inputSchema", Map.of("type", "object"))); }
        @Override public Outcome call(ToolContext ctx, String tool, Map<String, Object> args) {
            return switch (tool) {
                case "order_get" -> new Outcome(Map.of("orderId", args.get("orderId"), "status", "shipped"), false);
                case "order_create" -> throw new AgentToolException(AgentToolException.LIMIT_EXCEEDED, "limit_exceeded", "too big", Map.of("max", 10));
                case "boom" -> throw new IllegalStateException("kaboom");
                case "conflict" -> new Outcome(Map.of("error", "conflict"), true);
                default -> throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "Unknown tool: " + tool);
            };
        }
    };
    private final McpServer server = new McpServer(dispatcher, "test");
    private final ToolContext ctx = new ToolContext(new Principal(Principal.Type.AGENT, "bot", "test", Set.of("agent_shopper"), Set.of("order.read"), 2), Optional.empty(), Actor.agent("bot"));

    private JsonNode handle(String json) {
        McpServer.Response r = server.handle(Json.tree(json), ctx);
        return r.empty() ? null : Json.toTree(r.body());
    }

    @Test
    void initializeNegotiatesVersionAndAdvertisesTools() {
        JsonNode r = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"}}}");
        assertThat(r.path("id").asInt()).isEqualTo(1);
        assertThat(r.path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(r.path("result").path("serverInfo").path("name").asText()).isEqualTo("intent-oms");
        assertThat(r.path("result").path("capabilities").path("tools").path("listChanged").asBoolean()).isFalse();
        JsonNode unknown = handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertThat(unknown.path("result").path("protocolVersion").asText()).isEqualTo(McpServer.PROTOCOL_VERSION);
    }

    @Test
    void toolsCallWrapsStructuredContent() {
        JsonNode r = handle("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/call\",\"params\":{\"name\":\"order_get\",\"arguments\":{\"orderId\":\"o1\"}}}");
        assertThat(r.path("id").asText()).isEqualTo("a");
        JsonNode result = r.path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("status").asText()).isEqualTo("shipped");
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(Json.tree(result.path("content").get(0).path("text").asText()).path("orderId").asText()).isEqualTo("o1");
        JsonNode err = handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"conflict\",\"arguments\":{}}}");
        assertThat(err.path("result").path("isError").asBoolean()).isTrue();
        assertThat(err.has("error")).isFalse();
    }

    @Test
    void errorCodes() {
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}").path("error").path("code").asInt()).isEqualTo(JsonRpc.METHOD_NOT_FOUND);
        assertThat(handle("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}").path("error").path("code").asInt()).isEqualTo(JsonRpc.INVALID_REQUEST);
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1}").path("error").path("code").asInt()).isEqualTo(JsonRpc.INVALID_REQUEST);
        assertThat(handle("[]").path("error").path("code").asInt()).isEqualTo(JsonRpc.INVALID_REQUEST);
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"arguments\":{}}}").path("error").path("code").asInt()).isEqualTo(JsonRpc.INVALID_PARAMS);
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"zzz\"}}").path("error").path("code").asInt()).isEqualTo(JsonRpc.INVALID_PARAMS);
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":[1]}").path("error").path("code").asInt()).isEqualTo(JsonRpc.INVALID_PARAMS);
        JsonNode limit = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"order_create\",\"arguments\":{}}}");
        assertThat(limit.path("error").path("code").asInt()).isEqualTo(-32001);
        assertThat(limit.path("error").path("data").path("reason").asText()).isEqualTo("limit_exceeded");
        assertThat(limit.path("error").path("data").path("max").asInt()).isEqualTo(10);
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"boom\"}}").path("error").path("code").asInt()).isEqualTo(JsonRpc.INTERNAL_ERROR);
    }

    @Test
    void batchesAndNotifications() {
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
        JsonNode batch = handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"},{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"x\"}]");
        assertThat(batch.isArray()).isTrue();
        assertThat(batch).hasSize(3);
        assertThat(batch.get(0).path("id").asInt()).isEqualTo(1);
        assertThat(batch.get(0).path("result").isObject()).isTrue();
        assertThat(batch.get(1).path("result").path("tools").get(0).path("name").asText()).isEqualTo("order_get");
        assertThat(batch.get(2).path("error").path("code").asInt()).isEqualTo(JsonRpc.METHOD_NOT_FOUND);
        assertThat(handle("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}]")).as("all notifications → empty").isNull();
        assertThat(server.handle(null, ctx).body().toString()).contains("-32600");
    }
}
