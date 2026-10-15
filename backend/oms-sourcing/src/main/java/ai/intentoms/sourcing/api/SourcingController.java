package ai.intentoms.sourcing.api;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.sourcing.plan.SourcingPlanRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sourcing endpoints (FR-SRC-02..04, 07). {@code POST .../sourcing} returns 200 for every outcome; the body's
 * {@code status} says SOURCED, REQUIRES_APPROVAL or NO_FEASIBLE_NODE.
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}")
public class SourcingController {
    public record ResourceRequest(String reason, String nodeId) {}

    private final SourcingService sourcing;
    private final SourcingPlanRepository plans;
    private final AuthorizationService authz;

    public SourcingController(SourcingService sourcing, SourcingPlanRepository plans, AuthorizationService authz) {
        this.sourcing = sourcing;
        this.plans = plans;
        this.authz = authz;
    }

    @GetMapping("/sourcing/options")
    @RequiresPermission(Permissions.ORDER_READ)
    public List<SourcingPlan> options(@PathVariable String orderId) { return sourcing.options(orderId); }

    /** Plans considered in previous runs (chosen flag set on the persisted one). */
    @GetMapping("/sourcing/plans")
    @RequiresPermission(Permissions.ORDER_READ)
    public List<SourcingPlanRepository.Row> plans(@PathVariable String orderId) { return plans.forOrder(orderId); }

    @PostMapping("/sourcing")
    @RequiresPermission({Permissions.ORDER_MODIFY, Permissions.ORDER_RELEASE})
    public SourcingResult source(@PathVariable String orderId) { return sourcing.source(orderId, Actor.parse(authz.actorRef())); }

    @PostMapping("/resource")
    @RequiresPermission({Permissions.ORDER_MODIFY, Permissions.ORDER_RELEASE})
    public SourcingResult resource(@PathVariable String orderId, @RequestBody(required = false) ResourceRequest body) {
        String reason = body == null || body.reason() == null || body.reason().isBlank() ? "store_reject" : body.reason();
        return sourcing.resource(orderId, body == null ? null : body.nodeId(), reason, Actor.parse(authz.actorRef()));
    }
}
