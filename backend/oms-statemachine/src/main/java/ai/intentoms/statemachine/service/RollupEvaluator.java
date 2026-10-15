package ai.intentoms.statemachine.service;

import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.statemachine.model.RollupRule;
import ai.intentoms.statemachine.model.StateDef;
import ai.intentoms.statemachine.model.StateModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a header state from child quantities (FR-SM-05). Rules run in ascending priority (declaration order breaks
 * ties); the first true expression wins. A rule whose expression fails to evaluate (e.g. references a child state id the
 * header model does not declare without {@code has()}) counts as not matched. When no rule matches the fallback picks the
 * child state with the most quantity: the terminal one when every unit is terminal, otherwise the non-terminal one
 * (terminality judged by the header model where it declares the id, else by {@code childTerminal}).
 */
@Component
public class RollupEvaluator {
    private static final Logger log = LoggerFactory.getLogger(RollupEvaluator.class);
    private final CelEngine cel;

    public RollupEvaluator(CelEngine cel) { this.cel = cel; }

    public String evaluate(StateModel parent, Map<String, Integer> childStateQty) {
        return evaluate(parent, childStateQty, List.of(), List.of());
    }

    /**
     * @param zeroFillStates  extra state ids bound as 0 in the CEL {@code state} map (typically the child model's states)
     * @param childTerminal   child state ids known to be terminal (used by the fallback for ids the header model lacks)
     */
    public String evaluate(StateModel parent, Map<String, Integer> childStateQty, Collection<String> zeroFillStates, Collection<String> childTerminal) {
        Map<String, Integer> qty = new LinkedHashMap<>();
        if (childStateQty != null) childStateQty.forEach((k, v) -> { if (v != null && v > 0) qty.put(k, v); });
        Map<String, Object> vars = vars(parent, qty, zeroFillStates);
        List<RollupRule> rules = new ArrayList<>(parent.rollup());
        rules.sort(Comparator.comparingInt(RollupRule::priority));
        for (RollupRule r : rules) {
            try {
                if (cel.evalBool(r.expression(), vars)) return r.parentState();
            } catch (CelEngine.CelException e) {
                log.debug("Rollup rule '{}' for {} v{} did not evaluate: {}", r.expression(), parent.id(), parent.version(), e.getMessage());
            }
        }
        return fallback(parent, qty, childTerminal);
    }

    /** Build the CEL roots used by roll-up rules. */
    public static Map<String, Object> vars(StateModel parent, Map<String, Integer> qty, Collection<String> zeroFillStates) {
        Map<String, Object> state = new LinkedHashMap<>();
        for (StateDef s : parent.states()) state.put(s.id(), 0L);
        if (zeroFillStates != null) for (String s : zeroFillStates) state.putIfAbsent(s, 0L);
        List<Object> lines = new ArrayList<>();
        List<String> withQty = new ArrayList<>();
        long total = 0;
        String maxState = null;
        int maxQty = -1;
        for (var e : qty.entrySet()) {
            state.put(e.getKey(), e.getValue().longValue());
            lines.add(Map.of("state", e.getKey(), "qty", e.getValue().longValue()));
            withQty.add(e.getKey());
            total += e.getValue();
            if (e.getValue() > maxQty) { maxQty = e.getValue(); maxState = e.getKey(); }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("total", total);
        ctx.put("states_with_qty", withQty);
        ctx.put("distinct", (long) withQty.size());
        ctx.put("max_state", maxState == null ? "" : maxState);
        ctx.put("single_state", withQty.size() == 1);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("state", state);
        vars.put("lines", lines);
        vars.put("ctx", ctx);
        return vars;
    }

    public static String fallback(StateModel parent, Map<String, Integer> qty, Collection<String> childTerminal) {
        if (qty.isEmpty()) return parent.initial().id();
        String bestActive = null, bestTerminal = null;
        int activeQty = -1, terminalQty = -1;
        for (var e : qty.entrySet()) {
            boolean terminal = parent.hasState(e.getKey()) ? parent.isTerminal(e.getKey()) : childTerminal != null && childTerminal.contains(e.getKey());
            if (terminal) {
                if (e.getValue() > terminalQty) { bestTerminal = e.getKey(); terminalQty = e.getValue(); }
            } else if (e.getValue() > activeQty) { bestActive = e.getKey(); activeQty = e.getValue(); }
        }
        return bestActive != null ? bestActive : bestTerminal;
    }
}
