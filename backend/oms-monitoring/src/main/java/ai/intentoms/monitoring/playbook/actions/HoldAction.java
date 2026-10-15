package ai.intentoms.monitoring.playbook.actions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in {@code hold} step (FR-MON-04, FR-SM-07): places a typed hold on the exception's order. Parameters:
 * {@code holdType} (default {@code manual}), {@code blocks} (triggers/states, default {@code release}), {@code ttl} (ISO).
 * An identical active hold is reused rather than duplicated.
 */
@Component
public class HoldAction implements PlaybookAction {
    private final HoldService holds;

    public HoldAction(HoldService holds) { this.holds = holds; }

    @Override public String action() { return "hold"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        String orderId = ctx.orderId();
        if (orderId == null) return PlaybookStepResult.skipped("no order to hold");
        String type = step.get("holdType") == null ? "manual" : String.valueOf(step.get("holdType"));
        Set<String> blocks = new LinkedHashSet<>();
        if (step.get("blocks") instanceof List<?> l) l.forEach(b -> blocks.add(String.valueOf(b)));
        else if (step.get("blocks") != null) blocks.add(String.valueOf(step.get("blocks")));
        if (blocks.isEmpty()) blocks.add("release");
        for (Hold h : holds.active("order", orderId)) {
            if (h.type().equals(type) && h.blocksTransitions().containsAll(blocks)) return PlaybookStepResult.skipped("hold " + h.id() + " (" + type + ") already active");
        }
        Duration ttl = step.get("ttl") == null ? null : Duration.parse(String.valueOf(step.get("ttl")));
        Hold placed = holds.place(type, "order", orderId, blocks, ctx.exception().type(), "Playbook on exception " + ctx.exception().id() + ": " + ctx.exception().message(), ttl, ctx.actor());
        return PlaybookStepResult.done("hold " + placed.id() + " (" + type + ") blocks " + blocks, Map.of("holdId", placed.id()));
    }
}
