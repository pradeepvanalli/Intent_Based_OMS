package ai.intentoms.orchestration.bulk;

import ai.intentoms.order.modification.Feasibility;
import ai.intentoms.order.modification.Modification;
import ai.intentoms.order.modification.OrderModificationService;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.workflow.bulk.BulkActionHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bulk {@code cancel} (FR-WF-07, UC-07): cancels each order through the modification service (feasibility check, state
 * transitions, workflow signal). Dry run reports the feasibility verdict. Cancellation is not reversible.
 */
@Component
public class CancelBulkHandler implements BulkActionHandler {
    private final OrderModificationService modifications;
    private final AuthorizationService authz;

    public CancelBulkHandler(OrderModificationService modifications, AuthorizationService authz) {
        this.modifications = modifications;
        this.authz = authz;
    }

    @Override public String action() { return "cancel"; }

    @Override
    public Map<String, Object> apply(String orderId, Map<String, Object> params, boolean dryRun) {
        String reason = params.get("reason") == null ? "bulk cancel" : String.valueOf(params.get("reason"));
        Feasibility f = modifications.check(orderId, new Modification.CancelOrder());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("allowed", f.allowed());
        out.put("reasons", f.reasons());
        out.put("requiresApproval", f.requiresApproval());
        if (dryRun) { out.put("wouldCancel", f.allowed()); return out; }
        if (!f.allowed()) throw new ai.intentoms.platform.error.ConflictException("Order " + orderId + " cannot be cancelled: " + f.reasons(), Map.of("reasons", f.reasons()));
        modifications.apply(orderId, new Modification.CancelOrder(), reason, Actor.parse(authz.actorRef()));
        out.put("cancelled", true);
        out.put("reason", reason);
        return out;
    }

    @Override public Optional<Map<String, Object>> rollback(String orderId, Map<String, Object> applied) { return Optional.empty(); }
}
