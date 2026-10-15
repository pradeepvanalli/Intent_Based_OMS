package ai.intentoms.statemachine.service;

import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.statemachine.model.RollupRule;
import ai.intentoms.statemachine.model.StateDef;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import ai.intentoms.statemachine.model.TransitionDef;
import ai.intentoms.statemachine.model.ValidationIssue;
import ai.intentoms.statemachine.model.ValidationReport;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Static checks that gate publishing (FR-SM-10). Errors block; warnings are reported only.
 * <ul>
 *   <li>{@code missing_model_id}, {@code missing_entity}, {@code invalid_version}, {@code no_states}</li>
 *   <li>{@code duplicate_state}, {@code missing_initial}, {@code multiple_initial}, {@code missing_terminal}, {@code unknown_parent}</li>
 *   <li>{@code unknown_state} (transition source/target), {@code invalid_trigger}, {@code unknown_trigger_kind} (warning)</li>
 *   <li>{@code unreachable_state}, {@code dead_end} (non-terminal state without outgoing transition), {@code terminal_outgoing} (warning)</li>
 *   <li>{@code missing_permission} ({@code api.*} trigger without permission)</li>
 *   <li>{@code invalid_guard}, {@code unknown_guard_root} (CEL that fails to compile or references an undeclared root)</li>
 *   <li>{@code unbounded_loop} (warning: a cycle of unguarded event/decision transitions, i.e. nothing bounds it)</li>
 *   <li>{@code invalid_sla_on_breach} (must be {@code playbook.<x>} or {@code decision.<x>})</li>
 *   <li>{@code unknown_rollup_state}, {@code invalid_rollup_expression}, {@code unknown_rollup_root}, {@code duplicate_transition} (warning)</li>
 * </ul>
 */
@Component
public class StateModelValidator {
    private static final Pattern ON_BREACH = Pattern.compile("^(playbook|decision)\\.[A-Za-z0-9_\\-]+$");
    private static final Pattern TRIGGER = Pattern.compile("^[a-z][a-z0-9_]*\\.[A-Za-z0-9_\\-]+$");
    private static final Set<String> TRIGGER_KINDS = Set.of("event", "api", "timer", "decision");
    private static final Set<String> ROLLUP_ROOTS = Set.of("state", "lines", "ctx");

    private final CelEngine cel;

    public StateModelValidator(CelEngine cel) { this.cel = cel; }

    /** Parse then validate; a parse failure yields a single {@code parse_error}. */
    public ValidationReport validateYaml(String yaml) {
        StateModel model;
        try { model = StateModelParser.parse(yaml); }
        catch (IllegalArgumentException e) { return ValidationReport.of(List.of(ValidationIssue.error("parse_error", e.getMessage(), "yaml"))); }
        return validate(model);
    }

    public ValidationReport validate(StateModel m) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (m.id() == null || m.id().isBlank()) issues.add(ValidationIssue.error("missing_model_id", "stateModel id is required", "stateModel"));
        if (m.entity() == null || m.entity().isBlank()) issues.add(ValidationIssue.error("missing_entity", "entity is required (order, order_line, return, shipment, ...)", "entity"));
        if (m.version() < 1) issues.add(ValidationIssue.error("invalid_version", "version must be >= 1", "version"));
        if (m.states().isEmpty()) {
            issues.add(ValidationIssue.error("no_states", "model declares no states", "states"));
            return ValidationReport.of(issues);
        }

        Map<String, StateDef> states = new HashMap<>();
        for (StateDef s : m.states()) {
            if (states.putIfAbsent(s.id(), s) != null) issues.add(ValidationIssue.error("duplicate_state", "state '" + s.id() + "' is declared more than once", "state:" + s.id()));
        }
        long initials = m.states().stream().filter(StateDef::initial).count();
        if (initials == 0) issues.add(ValidationIssue.error("missing_initial", "no state has meta Created", "states"));
        if (initials > 1) issues.add(ValidationIssue.error("multiple_initial", initials + " states have meta Created; exactly one is allowed", "states"));
        if (m.states().stream().noneMatch(StateDef::terminal)) issues.add(ValidationIssue.error("missing_terminal", "no state has meta Terminal", "states"));
        for (StateDef s : m.states()) {
            if (s.parent() != null && !states.containsKey(s.parent()))
                issues.add(ValidationIssue.error("unknown_parent", "state '" + s.id() + "' names unknown parent '" + s.parent() + "'", "state:" + s.id()));
            if (s.parent() != null && s.parent().equals(s.id()))
                issues.add(ValidationIssue.error("unknown_parent", "state '" + s.id() + "' cannot be its own parent", "state:" + s.id()));
            if (s.sla() != null) {
                String ob = s.sla().onBreach();
                if (ob == null || !ON_BREACH.matcher(ob).matches())
                    issues.add(ValidationIssue.error("invalid_sla_on_breach", "state '" + s.id() + "': sla.onBreach must be playbook.<name> or decision.<name>, got '" + ob + "'", "state:" + s.id()));
            }
        }

        Set<String> seenTransitions = new HashSet<>();
        for (TransitionDef t : m.transitions()) {
            String ref = "transition:" + t.id();
            for (String f : t.from()) if (!states.containsKey(f)) issues.add(ValidationIssue.error("unknown_state", "transition '" + t.id() + "' has unknown source state '" + f + "'", ref));
            if (!states.containsKey(t.to())) issues.add(ValidationIssue.error("unknown_state", "transition '" + t.id() + "' has unknown target state '" + t.to() + "'", ref));
            if (t.trigger() == null || !TRIGGER.matcher(t.trigger()).matches()) {
                issues.add(ValidationIssue.error("invalid_trigger", "transition '" + t.id() + "' trigger must look like event.x, api.x, timer.x or decision.x", ref));
            } else {
                String kind = t.trigger().substring(0, t.trigger().indexOf('.'));
                if (!TRIGGER_KINDS.contains(kind)) issues.add(ValidationIssue.warning("unknown_trigger_kind", "transition '" + t.id() + "' uses trigger kind '" + kind + "' (expected event, api, timer or decision)", ref));
            }
            if (t.isApi() && (t.permission() == null || t.permission().isBlank()))
                issues.add(ValidationIssue.error("missing_permission", "transition '" + t.id() + "' is an API action and must declare a permission", ref));
            for (String g : t.guards()) checkCel(g, CelEngine.STANDARD_ROOTS, "invalid_guard", "unknown_guard_root", "guard", ref, issues);
            for (String f : t.from()) {
                if (!seenTransitions.add(f + "->" + t.to() + "#" + t.trigger()))
                    issues.add(ValidationIssue.warning("duplicate_transition", "transition " + f + "->" + t.to() + " on " + t.trigger() + " is declared more than once", ref));
            }
        }

        for (RollupRule r : m.rollup()) {
            String ref = "rollup:" + r.parentState();
            if (!states.containsKey(r.parentState())) issues.add(ValidationIssue.error("unknown_rollup_state", "rollup rule targets unknown state '" + r.parentState() + "'", ref));
            checkCel(r.expression(), ROLLUP_ROOTS, "invalid_rollup_expression", "unknown_rollup_root", "rollup expression", ref, issues);
        }

        if (initials == 1) {
            Set<String> reachable = reachable(m);
            for (StateDef s : m.states()) if (!reachable.contains(s.id()))
                issues.add(ValidationIssue.error("unreachable_state", "state '" + s.id() + "' cannot be reached from the Created state", "state:" + s.id()));
        }
        for (StateDef s : m.states()) {
            boolean outgoing = !m.transitionsFrom(s.id()).isEmpty();
            if (!s.terminal() && !outgoing) issues.add(ValidationIssue.error("dead_end", "non-terminal state '" + s.id() + "' has no outgoing transition", "state:" + s.id()));
            if (s.terminal() && outgoing) issues.add(ValidationIssue.warning("terminal_outgoing", "terminal state '" + s.id() + "' has outgoing transitions", "state:" + s.id()));
        }
        for (Set<String> cycle : unguardedCycles(m))
            issues.add(ValidationIssue.warning("unbounded_loop", "states " + cycle + " form a cycle with no guard or timer; add a guard or counter to bound it", "states:" + String.join(",", cycle)));
        return ValidationReport.of(issues);
    }

    private void checkCel(String expr, Set<String> allowedRoots, String invalidCode, String rootCode, String what, String ref, List<ValidationIssue> issues) {
        if (expr == null || expr.isBlank()) { issues.add(ValidationIssue.error(invalidCode, what + " is empty", ref)); return; }
        for (String root : cel.referencedRoots(expr)) {
            if (!CelEngine.STANDARD_ROOTS.contains(root) && !isIdentifierOnlyNoise(expr, root))
                issues.add(ValidationIssue.error(rootCode, what + " '" + expr + "' references undeclared root '" + root + "' (declared: " + new TreeSet<>(allowedRoots) + ")", ref));
            else if (CelEngine.STANDARD_ROOTS.contains(root) && !allowedRoots.contains(root))
                issues.add(ValidationIssue.error(rootCode, what + " '" + expr + "' references root '" + root + "' which is not available here (available: " + new TreeSet<>(allowedRoots) + ")", ref));
        }
        List<String> errors = cel.validate(expr);
        if (!errors.isEmpty()) issues.add(ValidationIssue.error(invalidCode, what + " '" + expr + "' is not valid CEL: " + String.join("; ", errors), ref));
    }

    /** Comprehension variables ({@code h} in {@code exists(h, ...)}) look like roots to the lexical scan; let the compiler judge those. */
    private static boolean isIdentifierOnlyNoise(String expr, String ident) {
        return Pattern.compile("[.(]\\s*(all|exists|exists_one|map|filter)\\s*\\(\\s*" + Pattern.quote(ident) + "\\s*,").matcher(expr).find();
    }

    private static Set<String> reachable(StateModel m) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        String init = m.initial().id();
        seen.add(init);
        q.add(init);
        while (!q.isEmpty()) {
            String s = q.poll();
            for (TransitionDef t : m.transitionsFrom(s)) if (seen.add(t.to())) q.add(t.to());
        }
        return seen;
    }

    /** Cycles over automatic transitions (event/decision triggers) without guards; api.* actions are human-bounded. Each cycle is reported once as its state set. */
    static List<Set<String>> unguardedCycles(StateModel m) {
        Map<String, List<String>> edges = new HashMap<>();
        for (TransitionDef t : m.transitions()) {
            if (t.guarded() || t.isTimer() || t.isApi()) continue;
            for (String f : t.from()) edges.computeIfAbsent(f, k -> new ArrayList<>()).add(t.to());
        }
        Set<Set<String>> found = new LinkedHashSet<>();
        for (StateDef s : m.states()) dfs(s.id(), edges, new ArrayList<>(), new HashSet<>(), found);
        return new ArrayList<>(found);
    }

    private static void dfs(String node, Map<String, List<String>> edges, List<String> stack, Set<String> onStack, Set<Set<String>> found) {
        if (onStack.contains(node)) {
            found.add(new TreeSet<>(stack.subList(stack.indexOf(node), stack.size())));
            return;
        }
        stack.add(node);
        onStack.add(node);
        for (String next : edges.getOrDefault(node, List.of())) dfs(next, edges, stack, onStack, found);
        stack.remove(stack.size() - 1);
        onStack.remove(node);
    }
}
