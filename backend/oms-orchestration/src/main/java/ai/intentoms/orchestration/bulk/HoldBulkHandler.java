package ai.intentoms.orchestration.bulk;

import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.bulk.BulkActionHandler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bulk {@code hold} (FR-WF-07, FR-SM-07): places a typed hold on each order. Params: {@code type} (default manual),
 * {@code blocks} (list or string, default release), {@code reason}, {@code note}, {@code ttl} (ISO). Dry run reports the
 * hold that would be placed; rollback releases it.
 */
@Component
public class HoldBulkHandler implements BulkActionHandler {
    private final HoldService holds;
    private final OrderService orders;
    private final AuthorizationService authz;

    public HoldBulkHandler(HoldService holds, OrderService orders, AuthorizationService authz) {
        this.holds = holds;
        this.orders = orders;
        this.authz = authz;
    }

    @Override public String action() { return "hold"; }

    @Override
    public Map<String, Object> apply(String orderId, Map<String, Object> params, boolean dryRun) {
        orders.get(orderId);
        String type = params.get("type") == null ? "manual" : String.valueOf(params.get("type"));
        Set<String> blocks = blocks(params.get("blocks"));
        String reason = params.get("reason") == null ? "bulk hold" : String.valueOf(params.get("reason"));
        String note = params.get("note") == null ? null : String.valueOf(params.get("note"));
        Duration ttl = null;
        if (params.get("ttl") != null) {
            try { ttl = Duration.parse(String.valueOf(params.get("ttl"))); } catch (DateTimeParseException e) { throw new ai.intentoms.platform.error.ValidationException("ttl must be an ISO-8601 duration"); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("type", type);
        out.put("blocks", List.copyOf(blocks));
        out.put("reason", reason);
        for (Hold h : holds.active("order", orderId)) {
            if (h.type().equals(type) && h.blocksTransitions().containsAll(blocks)) { out.put("skipped", "hold " + h.id() + " already active"); out.put("holdId", h.id()); return out; }
        }
        if (dryRun) { out.put("wouldPlace", true); return out; }
        Hold h = holds.place(type, "order", orderId, blocks, reason, note, ttl, Actor.parse(authz.actorRef()));
        out.put("holdId", h.id());
        return out;
    }

    @Override
    public Optional<Map<String, Object>> rollback(String orderId, Map<String, Object> applied) {
        Object holdId = applied.get("holdId");
        if (holdId == null || applied.containsKey("skipped")) return Optional.empty();
        holds.release(String.valueOf(holdId), "bulk rollback", Actor.parse(authz.actorRef()));
        return Optional.of(Map.of("released", holdId));
    }

    static Set<String> blocks(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof List<?> l) l.forEach(b -> out.add(String.valueOf(b)));
        else if (v != null && !String.valueOf(v).isBlank()) for (String b : String.valueOf(v).split(",")) out.add(b.trim());
        if (out.isEmpty()) out.add("release");
        return out;
    }
}
