package ai.intentoms.learning.promotion;

import ai.intentoms.decision.definition.DefinitionEditor;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a changeset into the artifact it would produce, with no side effects. Replay (EX-01), shadow (EX-02) and
 * promotion (EX-07) all build on this, so what is evaluated is exactly what is promoted.
 *
 * <p>Decision targets: ThresholdChange edits {@code bands.<band>}, WeightChange edits {@code composite.weights.<key>},
 * RuleAdd appends an L0 rule, RuleRetire removes one by id, ParameterChange edits a top-level field the decision owns
 * ({@code provider}, {@code fallbackProvider}, {@code timeoutMs}, {@code default}). Config targets
 * ({@code config:<key>}): WeightChange moves one weight of a weight map and rescales the others so the map still sums
 * to 1, AllowListChange adds or removes an entry, ParameterChange sets the value.
 */
public final class ChangesetApplier {
    private ChangesetApplier() {}

    static final List<String> DECISION_FIELDS = List.of("provider", "fallbackProvider", "timeoutMs", "default");

    /** True when every change can be applied to a decision definition. */
    public static boolean decisionApplicable(List<PolicyChange> changes) {
        if (changes == null || changes.isEmpty()) return false;
        for (PolicyChange c : changes) {
            if (c instanceof PolicyChange.AllowListChange) return false;
            if (c instanceof PolicyChange.ParameterChange pc && !DECISION_FIELDS.contains(pc.parameter())) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static String applyToDecision(String yaml, List<PolicyChange> changes) {
        if (!decisionApplicable(changes)) throw new ValidationException("Changeset contains a change that does not apply to a decision definition");
        Map<String, Object> model = Json.yamlMap(yaml);
        List<Map<String, Object>> rules = model.get("rules") instanceof List<?> l ? (List<Map<String, Object>>) (List<?>) l : List.of();
        Map<String, Object> edits = new LinkedHashMap<>();
        List<Integer> removals = new ArrayList<>();
        List<Map<String, Object>> additions = new ArrayList<>();
        for (PolicyChange c : changes) {
            switch (c) {
                case PolicyChange.ThresholdChange tc -> edits.put("bands." + tc.band().toLowerCase(Locale.ROOT), tc.to());
                case PolicyChange.WeightChange wc -> edits.put("weights." + wc.weightKey(), wc.to());
                case PolicyChange.ParameterChange pc -> edits.put(pc.parameter(), pc.to());
                case PolicyChange.RuleRetire rr -> {
                    int idx = -1;
                    for (int i = 0; i < rules.size(); i++) if (rr.ruleId().equals(String.valueOf(rules.get(i).get("id")))) idx = i;
                    if (idx < 0) throw new ValidationException("Rule '" + rr.ruleId() + "' does not exist on decision " + rr.decision());
                    if (!removals.contains(idx)) removals.add(idx);
                }
                case PolicyChange.RuleAdd ra -> {
                    Map<String, Object> rule = new LinkedHashMap<>();
                    rule.put("id", ruleId(ra));
                    rule.put("when", ra.expression());
                    rule.put("then", literal(ra.thenValue()));
                    rule.put("reason", ra.reason() == null || ra.reason().isBlank() ? "learned_rule" : ra.reason());
                    rule.put("priority", 50);
                    additions.add(rule);
                }
                case PolicyChange.AllowListChange al -> throw new ValidationException("AllowListChange applies to a config list, not a decision");
            }
        }
        // Removals first, highest index first, so earlier indexes stay valid; additions append after what remains.
        removals.sort((a, b) -> b - a);
        for (int idx : removals) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("rules[" + idx + "]", null);
            yaml = DefinitionEditor.apply(yaml, rm);
        }
        int size = rules.size() - removals.size();
        for (Map<String, Object> rule : additions) edits.put("rules[" + size++ + "]", rule);
        return edits.isEmpty() ? yaml : DefinitionEditor.apply(yaml, edits);
    }

    /** The new value of a tenant config key after applying one change. {@code current} may be null (key absent). */
    @SuppressWarnings("unchecked")
    public static Object applyToConfig(String key, Object current, PolicyChange change) {
        return switch (change) {
            case PolicyChange.ParameterChange pc -> pc.to();
            case PolicyChange.AllowListChange al -> {
                List<Object> list = current instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
                boolean present = list.stream().anyMatch(o -> al.entry().equals(String.valueOf(o)));
                if (al.add() && !present) list.add(al.entry());
                if (!al.add()) list.removeIf(o -> al.entry().equals(String.valueOf(o)));
                yield list;
            }
            case PolicyChange.WeightChange wc -> {
                Map<String, Object> m = current instanceof Map<?, ?> cm ? new LinkedHashMap<>((Map<String, Object>) cm) : new LinkedHashMap<>();
                Object existing = m.get(wc.weightKey());
                if (existing instanceof Number n && Math.abs(n.doubleValue() - wc.from()) > 1e-6) {
                    throw new ValidationException("Weight " + key + "." + wc.weightKey() + " is " + n + ", not " + wc.from() + " as the proposal assumed; re-draft it");
                }
                double othersNow = 0;
                for (var e : m.entrySet()) if (!e.getKey().equals(wc.weightKey()) && e.getValue() instanceof Number n) othersNow += n.doubleValue();
                double othersTarget = Math.max(0.0, 1.0 - wc.to());
                if (othersNow > 0) {
                    for (var e : m.entrySet()) {
                        if (!e.getKey().equals(wc.weightKey()) && e.getValue() instanceof Number n) e.setValue(round(n.doubleValue() * othersTarget / othersNow));
                    }
                }
                m.put(wc.weightKey(), round(wc.to()));
                yield m;
            }
            default -> throw new ValidationException(change.getClass().getSimpleName() + " cannot be applied to config key " + key);
        };
    }

    static String ruleId(PolicyChange.RuleAdd ra) {
        return "learned_" + Integer.toHexString((ra.decision() + "|" + ra.expression() + "|" + ra.thenValue()).hashCode() & 0x7fffffff);
    }

    /** Keeps numeric and boolean rule outcomes typed the way a hand-written YAML would have them. */
    static Object literal(String v) {
        if (v == null) return null;
        if (v.equals("true") || v.equals("false")) return Boolean.parseBoolean(v);
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(v); } catch (NumberFormatException ignored) {}
        return v;
    }

    private static double round(double d) { return Math.round(d * 1_000_000d) / 1_000_000d; }
}
