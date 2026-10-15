package ai.intentoms.agent.api;

import ai.intentoms.agent.AgentOrderVerifier;
import ai.intentoms.agent.evidence.EvidencePackService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Evidence pack and agent-order verification endpoints (FR-AGT-09, section 9 agent_mandate_match). */
@RestController
@RequestMapping("/api/v1/orders/{id}")
public class EvidencePackController {
    private final EvidencePackService packs;
    private final AgentOrderVerifier verifier;

    public EvidencePackController(EvidencePackService packs, AgentOrderVerifier verifier) {
        this.packs = packs;
        this.verifier = verifier;
    }

    public record Confirm(Boolean approved, String reason) {}

    /** 404 when the order has no agent provenance. */
    @GetMapping("/evidence-pack")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.AGENT_ADMIN})
    public Map<String, Object> evidencePack(@PathVariable String id) { return packs.build(id); }

    /** Runs the T3 mandate-match recommendation (normally invoked by the order workflow). */
    @PostMapping("/agent-verification")
    @RequiresPermission({Permissions.AGENT_ADMIN, Permissions.DECISION_OVERRIDE})
    public AgentOrderVerifier.Verification verify(@PathVariable String id) { return verifier.verify(id); }

    /** Human decision on an agent order: releases or keeps the agent_verification hold (GR-14). */
    @PostMapping("/agent-verification/confirm")
    @RequiresPermission({Permissions.AGENT_ADMIN, Permissions.DECISION_OVERRIDE})
    public AgentOrderVerifier.Verification confirm(@PathVariable String id, @RequestBody Confirm body) {
        return verifier.confirm(id, body != null && Boolean.TRUE.equals(body.approved()), body == null ? null : body.reason());
    }
}
