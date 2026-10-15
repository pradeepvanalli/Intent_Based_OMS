package ai.intentoms.orchestration.bulk;

import ai.intentoms.orchestration.actions.ActionSupport;
import ai.intentoms.orchestration.workflow.Signals;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.workflow.bulk.BulkActionHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bulk {@code resource} (FR-WF-07, UC-13): re-sources every order's active groups (optionally only those at {@code params.nodeId},
 * e.g. a store closed by a storm) and signals the workflow. Dry run reports the groups that would move. Not reversible: the
 * previous node already rejected or is unavailable.
 */
@Component
public class ResourceBulkHandler implements BulkActionHandler {
    private final SourcingService sourcing;
    private final ActionSupport support;
    private final AuthorizationService authz;

    public ResourceBulkHandler(SourcingService sourcing, ActionSupport support, AuthorizationService authz) {
        this.sourcing = sourcing;
        this.support = support;
        this.authz = authz;
    }

    @Override public String action() { return "resource"; }

    @Override
    public Map<String, Object> apply(String orderId, Map<String, Object> params, boolean dryRun) {
        Order order = support.orders().get(orderId);
        String nodeId = params.get("nodeId") == null ? null : String.valueOf(params.get("nodeId"));
        String reason = params.get("reason") == null ? "bulk re-source" : String.valueOf(params.get("reason"));
        List<FulfilmentGroup> groups = ActionSupport.activeGroups(order, nodeId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("groups", groups.stream().map(FulfilmentGroup::id).toList());
        out.put("nodes", groups.stream().map(FulfilmentGroup::nodeId).distinct().toList());
        if (groups.isEmpty()) { out.put("skipped", "no active fulfilment group" + (nodeId == null ? "" : " at " + nodeId)); return out; }
        if (dryRun) { out.put("wouldResource", true); return out; }
        SourcingResult r = sourcing.resource(orderId, nodeId, reason, Actor.parse(authz.actorRef()));
        out.put("status", r.status().name());
        out.put("planId", r.plan() == null ? "" : r.plan().id());
        out.put("newNodes", r.plan() == null ? List.of() : r.plan().packages().stream().map(p -> p.nodeId()).toList());
        out.put("signalled", support.signal(order, Signals.RESOURCED, Map.of("status", r.status().name(), "reason", reason), "bulk:resource:" + orderId + ":" + System.nanoTime()));
        return out;
    }

    @Override public Optional<Map<String, Object>> rollback(String orderId, Map<String, Object> applied) { return Optional.empty(); }
}
