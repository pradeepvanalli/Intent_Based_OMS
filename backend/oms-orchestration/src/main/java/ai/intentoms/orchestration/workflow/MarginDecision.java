package ai.intentoms.orchestration.workflow;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure branch logic for the outcome of a {@code margin_floor_approval} task (GR-01, FR-SRC-04, UC-03). Kept free of
 * Spring so it is unit-testable.
 * <ul>
 *   <li>{@code approved} (also accept/approve/yes) → {@link Kind#APPROVE}: the below-floor plan is released with {@code approval.present}.</li>
 *   <li>{@code alternative} / {@code use_alternative} / {@code rejected} with a {@code policy} (or {@code planId} that maps to a known
 *       alternative) in the details → {@link Kind#ALTERNATIVE}: the order's intent is set to that policy and sourcing re-runs.</li>
 *   <li>{@code rejected} without an alternative, or {@code cancel} → {@link Kind#CANCEL}.</li>
 * </ul>
 */
public final class MarginDecision {
    private MarginDecision() {}

    public enum Kind { APPROVE, ALTERNATIVE, CANCEL }

    /** @param policy the fulfilment policy to source with when {@code kind == ALTERNATIVE}, else null */
    public record Outcome(Kind kind, String policy, String reason) {}

    /** @param alternatives alternative plans as stored in the task payload ({@code planId} → {@code policy}) */
    public static Outcome decide(String outcome, Map<String, Object> details, List<Map<String, Object>> alternatives) {
        String o = outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> d = details == null ? Map.of() : details;
        String policy = chosenPolicy(d, alternatives);
        if (o.equals("approved") || o.equals("approve") || o.equals("accept") || o.equals("accepted") || o.equals("yes")) return new Outcome(Kind.APPROVE, null, "approved by finance");
        if (policy != null) return new Outcome(Kind.ALTERNATIVE, policy, "alternative policy " + policy + " chosen");
        if (o.equals("alternative") || o.equals("use_alternative")) {
            String first = alternatives == null || alternatives.isEmpty() ? null : str(alternatives.get(0).get("policy"));
            return first == null ? new Outcome(Kind.CANCEL, null, "alternative requested but none available") : new Outcome(Kind.ALTERNATIVE, first, "best alternative " + first);
        }
        return new Outcome(Kind.CANCEL, null, o.isEmpty() ? "rejected" : o);
    }

    private static String chosenPolicy(Map<String, Object> details, List<Map<String, Object>> alternatives) {
        String policy = str(details.get("policy"));
        if (policy != null && !policy.isBlank()) return policy.trim().toLowerCase(Locale.ROOT);
        String planId = str(details.get("planId"));
        if (planId != null && alternatives != null) {
            for (Map<String, Object> a : alternatives) if (planId.equals(str(a.get("planId"))) && a.get("policy") != null) return str(a.get("policy"));
        }
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
