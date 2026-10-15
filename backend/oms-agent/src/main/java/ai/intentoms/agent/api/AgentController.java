package ai.intentoms.agent.api;

import ai.intentoms.agent.registry.AgentActivity;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistered;
import ai.intentoms.agent.registry.AgentRegistration;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.agent.registry.AgentUpdate;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent registry API (UI contract item 12, FR-AGT-03/04). Writes need {@code agent.admin}; reads {@code order.read} or
 * {@code agent.admin}. Registration and rotation return secrets exactly once.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentRegistry registry;

    public AgentController(AgentRegistry registry) { this.registry = registry; }

    public record SuspendRequest(String reason) {}

    @GetMapping
    @RequiresPermission({Permissions.ORDER_READ, Permissions.AGENT_ADMIN})
    public List<AgentIdentity> list() { return registry.list(); }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.AGENT_ADMIN})
    public AgentIdentity get(@PathVariable String id) { return registry.get(id); }

    @PostMapping
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public ResponseEntity<AgentRegistered> register(@RequestBody AgentRegistration body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registry.register(body));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public AgentIdentity update(@PathVariable String id, @RequestBody AgentUpdate body) { return registry.update(id, body); }

    @PostMapping("/{id}/rotate-secret")
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public Map<String, String> rotate(@PathVariable String id) { return registry.rotateSecret(id); }

    @PostMapping("/{id}/suspend")
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public AgentIdentity suspend(@PathVariable String id, @RequestBody(required = false) SuspendRequest body) { return registry.suspend(id, body == null ? null : body.reason()); }

    @GetMapping("/{id}/activity")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.AGENT_ADMIN})
    public List<AgentActivity> activity(@PathVariable String id, @RequestParam(defaultValue = "100") int limit) { return registry.activity(id, limit); }
}
