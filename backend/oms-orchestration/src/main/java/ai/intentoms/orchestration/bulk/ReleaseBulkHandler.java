package ai.intentoms.orchestration.bulk;

import ai.intentoms.orchestration.actions.ActionSupport;
import ai.intentoms.orchestration.workflow.Signals;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.bulk.BulkActionHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bulk {@code release} (FR-WF-07, FR-SM-07): releases the order's active holds ({@code params.type} narrows to one hold type,
 * {@code params.holdId} to one hold) and signals the workflow {@code hold_released} so a blocked release resumes. Dry run
 * lists the holds that would be released. Not reversible (a released hold is re-placed through {@code hold}).
 */
@Component
public class ReleaseBulkHandler implements BulkActionHandler {
    private final HoldService holds;
    private final ActionSupport support;
    private final AuthorizationService authz;

    public ReleaseBulkHandler(HoldService holds, ActionSupport support, AuthorizationService authz) {
        this.holds = holds;
        this.support = support;
        this.authz = authz;
    }

    @Override public String action() { return "release"; }

    @Override
    public Map<String, Object> apply(String orderId, Map<String, Object> params, boolean dryRun) {
        Order order = support.orders().get(orderId);
        String type = params.get("type") == null ? null : String.valueOf(params.get("type"));
        String holdId = params.get("holdId") == null ? null : String.valueOf(params.get("holdId"));
        String resolution = params.get("resolution") == null ? "bulk release" : String.valueOf(params.get("resolution"));
        List<Hold> targets = holds.active("order", orderId).stream().filter(h -> type == null || type.equals(h.type())).filter(h -> holdId == null || holdId.equals(h.id())).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("holds", targets.stream().map(h -> h.type() + ":" + h.id()).toList());
        if (targets.isEmpty()) { out.put("skipped", "no matching active hold"); return out; }
        if (dryRun) { out.put("wouldRelease", targets.size()); return out; }
        List<String> released = new ArrayList<>();
        for (Hold h : targets) { holds.release(h.id(), resolution, Actor.parse(authz.actorRef())); released.add(h.id()); }
        out.put("released", released);
        out.put("signalled", support.signal(order, Signals.HOLD_RELEASED, Map.of("holdIds", released, "resolution", resolution), "bulk:release:" + orderId + ":" + released.get(0)));
        return out;
    }

    @Override public Optional<Map<String, Object>> rollback(String orderId, Map<String, Object> applied) { return Optional.empty(); }
}
