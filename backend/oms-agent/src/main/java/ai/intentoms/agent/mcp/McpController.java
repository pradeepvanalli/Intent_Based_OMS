package ai.intentoms.agent.mcp;

import ai.intentoms.agent.auth.AgentAuthFilter;
import ai.intentoms.agent.config.AgentProperties;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * MCP Streamable HTTP endpoint (FR-AGT-01, FR-AGT-06): {@code POST /mcp} with a JSON-RPC request or batch answered as
 * {@code application/json}; {@code GET /mcp} is 405 (no server-initiated SSE stream is offered); {@code DELETE /mcp}
 * ends the (stateless) session with 204. Honours and echoes {@code MCP-Protocol-Version}. Authentication comes from
 * {@link AgentAuthFilter} (signed requests), API keys or JWTs; unauthenticated calls get a 401 problem detail.
 */
@RestController
public class McpController {
    public static final String HEADER_PROTOCOL = "MCP-Protocol-Version";
    private final McpServer server;
    private final AuthorizationService authz;

    public McpController(McpToolExecutor executor, AuthorizationService authz, AgentProperties props) {
        this.server = new McpServer(executor, props.serverVersion());
        this.authz = authz;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> post(@RequestBody(required = false) String body, @RequestHeader(value = HEADER_PROTOCOL, required = false) String protocol, HttpServletRequest request) {
        if (protocol != null && !protocol.isBlank() && !McpServer.SUPPORTED_PROTOCOL_VERSIONS.contains(protocol.trim())) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unsupported MCP protocol version " + protocol);
            pd.setTitle("unsupported_protocol_version");
            pd.setProperty("supported", List.copyOf(McpServer.SUPPORTED_PROTOCOL_VERSIONS));
            return ResponseEntity.badRequest().header(HEADER_PROTOCOL, McpServer.PROTOCOL_VERSION).body(pd);
        }
        Optional<Principal> principal = authz.currentPrincipal();
        if (principal.isEmpty()) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authenticate with signed agent headers (X-Agent-Id, X-Agent-Timestamp, X-Agent-Signature), an API key (X-Api-Key) or a bearer JWT");
            pd.setTitle("unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HEADER_PROTOCOL, McpServer.PROTOCOL_VERSION).body(pd);
        }
        JsonNode tree;
        try { tree = body == null || body.isBlank() ? null : Json.tree(body); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.ok().header(HEADER_PROTOCOL, McpServer.PROTOCOL_VERSION).body(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error: " + e.getMessage(), null));
        }
        AgentIdentity identity = (AgentIdentity) request.getAttribute(AgentAuthFilter.ATTR_IDENTITY);
        ToolContext ctx = ToolContext.of(principal.get(), identity);
        McpServer.Response r = server.handle(tree, ctx);
        if (r.empty()) return ResponseEntity.accepted().header(HEADER_PROTOCOL, McpServer.PROTOCOL_VERSION).build();
        return ResponseEntity.ok().header(HEADER_PROTOCOL, McpServer.PROTOCOL_VERSION).contentType(MediaType.APPLICATION_JSON).body(r.body());
    }

    @GetMapping("/mcp")
    public ResponseEntity<ProblemDetail> get() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "This MCP server answers JSON-RPC requests on POST /mcp with application/json responses and does not open server-initiated SSE streams");
        pd.setTitle("method_not_allowed");
        pd.setProperty("protocolVersion", McpServer.PROTOCOL_VERSION);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST, DELETE").header(HEADER_PROTOCOL, McpServer.PROTOCOL_VERSION).body(pd);
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> delete() { return ResponseEntity.noContent().header(HEADER_PROTOCOL, McpServer.PROTOCOL_VERSION).build(); }
}
