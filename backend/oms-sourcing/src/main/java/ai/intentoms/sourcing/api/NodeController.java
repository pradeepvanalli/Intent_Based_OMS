package ai.intentoms.sourcing.api;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.node.DefaultNodeRegistry;
import ai.intentoms.sourcing.node.NodeLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Node CRUD and health/penalty hooks (FR-SRC-01, FR-SRC-07, FR-SRC-08). Reads need order.read, writes config.admin. */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {
    public record PenaltyRequest(String sku, double delta, String reason) {}

    private final NodeRegistry registry;
    private final DefaultNodeRegistry impl;

    public NodeController(NodeRegistry registry, DefaultNodeRegistry impl) {
        this.registry = registry;
        this.impl = impl;
    }

    @GetMapping
    @RequiresPermission({Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public List<Node> list(@RequestParam(defaultValue = "false") boolean includeInactive) { return includeInactive ? registry.all() : registry.active(); }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public Map<String, Object> get(@PathVariable String id) {
        Node n = registry.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", n);
        m.put("penalties", impl.penaltyRows(id));
        return m;
    }

    /** Creates or replaces a node. The body is the node definition (same shape as nodes.yaml entries). */
    @PutMapping("/{id}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Node upsert(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> def = new LinkedHashMap<>(body);
        if (def.get("id") != null && !id.equals(String.valueOf(def.get("id")))) throw new ValidationException("Path id and body id differ", Map.of("path", id, "body", def.get("id")));
        def.put("id", id);
        return registry.upsert(NodeLoader.fromMap(def));
    }

    /** FR-SRC-08: merge health signals (backlog, pick_rate, reject_rate, staffing, weather, carrier_capacity). */
    @PostMapping("/{id}/health")
    @RequiresPermission({Permissions.CONFIG_ADMIN, Permissions.INVENTORY_WRITE})
    public Node health(@PathVariable String id, @RequestBody Map<String, Object> signals) { return registry.updateHealth(id, signals); }

    /** FR-SRC-07: add a learned penalty for the node (sku optional). */
    /** Clears the learned penalties of a node, e.g. after an outage is resolved. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}/penalties")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> clearPenalties(@PathVariable String id, @org.springframework.web.bind.annotation.RequestParam(required = false) String reason) {
        int n = registry.clearPenalties(id, reason);
        Map<String, Object> out = new java.util.LinkedHashMap<>(get(id));
        out.put("cleared", n);
        return out;
    }

    @PostMapping("/{id}/penalty")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> penalise(@PathVariable String id, @RequestBody PenaltyRequest body) {
        registry.penalise(id, body.sku() == null || body.sku().isBlank() ? null : body.sku(), body.delta(), body.reason());
        return get(id);
    }
}
