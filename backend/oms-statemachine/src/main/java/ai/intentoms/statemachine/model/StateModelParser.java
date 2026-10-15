package ai.intentoms.statemachine.model;

import ai.intentoms.platform.json.Json;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the PRD Appendix A YAML format (FR-SM-09) into a {@link StateModel}:
 * <pre>
 * stateModel: order_line.standard_ship
 * version: 3
 * entity: order_line
 * quantityLevel: true
 * states:
 *   - { id: created, meta: Created }
 *   - { id: released, sla: { maxAge: PT4H, onBreach: playbook.store_backlog }, parent: fulfilment }
 * transitions:
 *   - { from: [a, b], to: c, trigger: api.cancel_line, guards: ["..."], actions: [x], permission: order.cancel }
 * rollup:
 *   - { parentState: shipped, when: "state.shipped == ctx.total", priority: 10 }
 * </pre>
 * Structural problems raise {@link IllegalArgumentException} with a message naming the offending element; semantic
 * problems (unknown states, dead ends, ...) are left to {@code StateModelValidator} so that a report can list them all.
 */
public final class StateModelParser {
    private static final Set<String> TRANSITION_KEYS = Set.of("id", "from", "to", "trigger", "guards", "guard", "actions", "action", "permission");

    private StateModelParser() {}

    public static StateModel parse(String yaml) {
        if (yaml == null || yaml.isBlank()) throw new IllegalArgumentException("State model YAML is empty");
        Map<String, Object> root = Json.yamlMap(yaml);
        String id = str(root.get("stateModel"), "stateModel");
        if (id == null) id = str(root.get("id"), "id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing 'stateModel' id");
        int version = root.get("version") == null ? 1 : toInt(root.get("version"), "version");
        String entity = str(root.get("entity"), "entity");
        boolean quantityLevel = Boolean.TRUE.equals(root.get("quantityLevel")) || "true".equalsIgnoreCase(String.valueOf(root.get("quantityLevel")));

        List<StateDef> states = new ArrayList<>();
        for (Object o : list(root.get("states"), "states")) states.add(parseState(o));
        List<TransitionDef> transitions = new ArrayList<>();
        for (Object o : list(root.get("transitions"), "transitions")) transitions.add(parseTransition(o));
        List<RollupRule> rollup = new ArrayList<>();
        int i = 0;
        for (Object o : list(root.get("rollup"), "rollup")) rollup.add(parseRollup(o, i++));
        return new StateModel(id, version, entity, quantityLevel, states, transitions, rollup, yaml);
    }

    @SuppressWarnings("unchecked")
    private static StateDef parseState(Object o) {
        if (o instanceof String s) return new StateDef(s, MetaState.ACTIVE, null, null, List.of());
        if (!(o instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Each state must be a mapping or a string, got " + o);
        Map<String, Object> m = (Map<String, Object>) raw;
        String id = str(m.get("id"), "state.id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("State without id: " + m);
        MetaState meta = MetaState.parse(str(m.get("meta"), "state.meta"));
        SlaDef sla = null;
        Object slaRaw = m.get("sla");
        if (slaRaw != null) {
            if (!(slaRaw instanceof Map<?, ?> sm)) throw new IllegalArgumentException("State '" + id + "': sla must be a mapping {maxAge, onBreach}");
            Object maxAge = sm.get("maxAge");
            if (maxAge == null) throw new IllegalArgumentException("State '" + id + "': sla.maxAge is required");
            Duration d;
            try { d = Duration.parse(maxAge.toString()); }
            catch (DateTimeParseException e) { throw new IllegalArgumentException("State '" + id + "': sla.maxAge '" + maxAge + "' is not an ISO-8601 duration"); }
            if (d.isNegative() || d.isZero()) throw new IllegalArgumentException("State '" + id + "': sla.maxAge must be positive");
            Object onBreach = sm.get("onBreach");
            sla = new SlaDef(d, onBreach == null ? null : onBreach.toString());
        }
        return new StateDef(id, meta, sla, str(m.get("parent"), "state.parent"), strings(m.get("blocksIfHeld"), "state.blocksIfHeld"));
    }

    @SuppressWarnings("unchecked")
    private static TransitionDef parseTransition(Object o) {
        if (!(o instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Each transition must be a mapping, got " + o);
        Map<String, Object> m = (Map<String, Object>) raw;
        List<String> from = strings(m.get("from"), "transition.from");
        String to = str(m.get("to"), "transition.to");
        String trigger = str(m.get("trigger"), "transition.trigger");
        if (from.isEmpty()) throw new IllegalArgumentException("Transition without 'from': " + m);
        if (to == null || to.isBlank()) throw new IllegalArgumentException("Transition from " + from + " without 'to'");
        if (trigger == null || trigger.isBlank()) throw new IllegalArgumentException("Transition " + from + "->" + to + " without 'trigger'");
        List<String> guards = new ArrayList<>(strings(m.get("guards"), "transition.guards"));
        guards.addAll(strings(m.get("guard"), "transition.guard"));
        List<String> actions = new ArrayList<>(strings(m.get("actions"), "transition.actions"));
        actions.addAll(strings(m.get("action"), "transition.action"));
        Map<String, Object> extras = new LinkedHashMap<>();
        for (var e : m.entrySet()) if (!TRANSITION_KEYS.contains(e.getKey()) && e.getValue() != null) extras.put(e.getKey(), e.getValue());
        return new TransitionDef(str(m.get("id"), "transition.id"), from, to, trigger, guards, actions, str(m.get("permission"), "transition.permission"), extras);
    }

    @SuppressWarnings("unchecked")
    private static RollupRule parseRollup(Object o, int index) {
        if (!(o instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Each rollup rule must be a mapping, got " + o);
        Map<String, Object> m = (Map<String, Object>) raw;
        String parent = str(m.get("parentState"), "rollup.parentState");
        if (parent == null) parent = str(m.get("state"), "rollup.state");
        String when = str(m.get("when"), "rollup.when");
        if (when == null) when = str(m.get("expression"), "rollup.expression");
        if (parent == null || parent.isBlank()) throw new IllegalArgumentException("Rollup rule #" + index + " without 'parentState'");
        if (when == null || when.isBlank()) throw new IllegalArgumentException("Rollup rule for '" + parent + "' without 'when' expression");
        int priority = m.get("priority") == null ? (index + 1) * 10 : toInt(m.get("priority"), "rollup.priority");
        return new RollupRule(parent, when, priority);
    }

    private static List<Object> list(Object o, String field) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return new ArrayList<>(l);
        throw new IllegalArgumentException("'" + field + "' must be a list");
    }

    private static List<String> strings(Object o, String field) {
        if (o == null) return List.of();
        if (o instanceof String s) return s.isBlank() ? List.of() : List.of(s.trim());
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object e : l) {
                if (e == null) continue;
                if (!(e instanceof String || e instanceof Number || e instanceof Boolean)) throw new IllegalArgumentException("'" + field + "' must contain only strings");
                out.add(e.toString().trim());
            }
            return out;
        }
        throw new IllegalArgumentException("'" + field + "' must be a string or a list of strings");
    }

    private static String str(Object o, String field) {
        if (o == null) return null;
        if (o instanceof Map || o instanceof List) throw new IllegalArgumentException("'" + field + "' must be a scalar");
        return o.toString();
    }

    private static int toInt(Object o, String field) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("'" + field + "' must be an integer, got '" + o + "'"); }
    }
}
