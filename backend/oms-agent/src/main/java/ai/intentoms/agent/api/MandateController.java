package ai.intentoms.agent.api;

import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.agent.mandate.MandateCreate;
import ai.intentoms.agent.mandate.MandateService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Mandate API (UI contract item 12, FR-CAP-07, FR-FIN-06): list by agent, get, issue, revoke. */
@RestController
@RequestMapping("/api/v1/mandates")
public class MandateController {
    private final MandateService mandates;

    public MandateController(MandateService mandates) { this.mandates = mandates; }

    public record RevokeRequest(String reason) {}

    @GetMapping
    @RequiresPermission({Permissions.ORDER_READ, Permissions.AGENT_ADMIN})
    public List<Mandate> list(@RequestParam(required = false) String agentId, @RequestParam(defaultValue = "200") int limit) {
        return agentId == null || agentId.isBlank() ? mandates.list(limit) : mandates.listForAgent(agentId);
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.AGENT_ADMIN})
    public Mandate get(@PathVariable String id) { return mandates.get(id); }

    @PostMapping
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public ResponseEntity<Mandate> create(@RequestBody MandateCreate body) { return ResponseEntity.status(HttpStatus.CREATED).body(mandates.create(body)); }

    @PostMapping("/{id}/revoke")
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public Mandate revoke(@PathVariable String id, @RequestBody(required = false) RevokeRequest body) { return mandates.revoke(id, body == null ? null : body.reason()); }
}
