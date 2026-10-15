package ai.intentoms.orchestration.bulk;

import ai.intentoms.orchestration.actions.ActionSupport;
import ai.intentoms.orchestration.actions.RepromiseAction;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.workflow.bulk.BulkActionHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bulk {@code repromise} (FR-WF-07, UC-13): recomputes promise dates; dry run lists the current promises. */
@Component
public class RepromiseBulkHandler implements BulkActionHandler {
    private final RepromiseAction repromise;
    private final ActionSupport support;

    public RepromiseBulkHandler(RepromiseAction repromise, ActionSupport support) {
        this.repromise = repromise;
        this.support = support;
    }

    @Override public String action() { return "repromise"; }

    @Override
    public Map<String, Object> apply(String orderId, Map<String, Object> params, boolean dryRun) {
        Order order = support.orders().get(orderId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("currentPromises", ActionSupport.activeGroups(order, null).stream().<Map<String, Object>>map(g -> Map.of("groupId", g.id(), "nodeId", String.valueOf(g.nodeId()), "promiseDate", String.valueOf(g.promiseDate()))).toList());
        if (dryRun) { out.put("wouldRepromise", !ActionSupport.activeGroups(order, null).isEmpty()); return out; }
        String template = params.get("notify") != null && !Boolean.parseBoolean(String.valueOf(params.get("notify"))) ? null : params.get("template") == null ? "delay_notice" : String.valueOf(params.get("template"));
        out.putAll(repromise.repromise(order, template, null, params.get("reason") == null ? "bulk re-promise" : String.valueOf(params.get("reason"))));
        return out;
    }

    @Override public Optional<Map<String, Object>> rollback(String orderId, Map<String, Object> applied) { return Optional.empty(); }
}
