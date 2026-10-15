package ai.intentoms.monitoring.playbook;

import ai.intentoms.platform.cel.CelEngine;

import java.util.Map;
import java.util.Optional;

/**
 * Pure gate evaluation for one playbook step (FR-MON-04): {@code onFailure} (run only after a failed step),
 * {@code when} (CEL over {@code exception}, {@code order}, {@code tenant}), {@code minConfidence} (against the decision
 * confidence) and spend limits ({@code amountUsd} against the category limit). Returns the first gate that blocks.
 */
public final class PlaybookGate {
    public static final String ON_FAILURE = "onFailure";
    public static final String WHEN = "when";
    public static final String MIN_CONFIDENCE = "minConfidence";
    public static final String SPEND_LIMIT = "spendLimit";
    public static final String REQUIRES_DECISION = "requiresDecision";

    /** Category key in {@code spendLimits} for actions with an inherent spend. */
    private static final Map<String, String> ACTION_CATEGORY = Map.of("reship", "reship_usd", "refund", "refund_usd", "appeasement", "appeasement_usd", "contact_customer", "appeasement_usd");

    public record Verdict(boolean pass, String gatedBy, String detail) {
        public static final Verdict PASS = new Verdict(true, null, null);
        static Verdict blocked(String gate, String detail) { return new Verdict(false, gate, detail); }
    }

    private final CelEngine cel;

    public PlaybookGate(CelEngine cel) { this.cel = cel; }

    public Verdict evaluate(Map<String, Object> step, double confidence, Map<String, Object> celVars, boolean previousFailed, Map<String, Object> spendLimits) {
        if (truthy(step.get(ON_FAILURE)) && !previousFailed) return Verdict.blocked(ON_FAILURE, "no earlier step failed");
        Object when = step.get(WHEN);
        if (when != null && !String.valueOf(when).isBlank()) {
            try {
                if (!cel.evalBool(String.valueOf(when), celVars)) return Verdict.blocked(WHEN, "condition false: " + when);
            } catch (RuntimeException e) {
                return Verdict.blocked(WHEN, "condition error: " + e.getMessage());
            }
        }
        Optional<Double> min = number(step.get(MIN_CONFIDENCE));
        if (min.isPresent() && confidence < min.get()) return Verdict.blocked(MIN_CONFIDENCE, "confidence " + round(confidence) + " < " + min.get());
        Optional<Double> amount = number(step.get("amountUsd")).or(() -> number(step.get("amount_usd")));
        if (amount.isPresent()) {
            String category = step.get("spendCategory") != null ? String.valueOf(step.get("spendCategory")) : ACTION_CATEGORY.get(String.valueOf(step.get("action")));
            if (category != null && spendLimits.get(category) instanceof Number limit && amount.get() > limit.doubleValue())
                return Verdict.blocked(SPEND_LIMIT, "amount " + amount.get() + " exceeds " + category + " limit " + limit);
        }
        return Verdict.PASS;
    }

    static boolean truthy(Object o) { return o instanceof Boolean b ? b : o != null && "true".equalsIgnoreCase(o.toString()); }

    static Optional<Double> number(Object o) {
        if (o instanceof Number n) return Optional.of(n.doubleValue());
        if (o instanceof String s && !s.isBlank()) { try { return Optional.of(Double.parseDouble(s)); } catch (NumberFormatException ignored) { } }
        return Optional.empty();
    }

    private static double round(double d) { return Math.round(d * 1000) / 1000.0; }
}
