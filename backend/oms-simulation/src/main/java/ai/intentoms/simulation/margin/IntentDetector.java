package ai.intentoms.simulation.margin;

import ai.intentoms.sourcing.model.Policies;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fulfilment intent of an imported order (Margin Replay). The primary source is the tenant's {@code fulfilment_intent}
 * decision run in simulation mode; this class holds the result shape and a deterministic keyword fallback used when
 * decisions are switched off or unavailable, so the report always has an intent column.
 */
public final class IntentDetector {
    /**
     * @param policy  ship_complete | ship_as_available | earliest_partial | pickup_consolidated, or null when nothing was detected
     * @param splitOk the buyer's tolerance for several packages when known
     * @param source  decision | keywords | none
     * @param band    the decision band (ACT | CONFIRM | ESCALATE) when a decision ran
     * @param label   gift | urgent | together | pickup | separate | none: the headline intent for the report
     */
    public record DetectedIntent(String policy, boolean gift, boolean urgent, boolean together, boolean pickup, Boolean splitOk, String source, double confidence,
                                 String band, String recordId, String label) {
        public static DetectedIntent none() { return new DetectedIntent(null, false, false, false, false, null, "none", 0.0, null, null, "none"); }

        public boolean any() { return policy != null || gift; }

        public DetectedIntent withDecision(String band, String recordId, double confidence) {
            return new DetectedIntent(policy, gift, urgent, together, pickup, splitOk, "decision", confidence, band, recordId, label);
        }
    }

    private static final Pattern TOGETHER = Pattern.compile("(?i)\\b(together|one (box|package|parcel|shipment|delivery)|single (box|package|shipment)|all at once|(don'?t|do not|please no) split|same (box|shipment)|consolidate)");
    private static final Pattern URGENT = Pattern.compile("(?i)\\b(urgent(ly)?|asap|as soon as possible|rush|hurry|expedite|need(s|ed)? (it|this|them|the \\w+) (by|before)|before (mon|tues|wednes|thurs|fri|satur|sun)day|before the weekend|by (tomorrow|friday|saturday|sunday|monday)|in time for|time.sensitive|first,? the rest)");
    private static final Pattern SEPARATE = Pattern.compile("(?i)\\b(separate(ly)? (boxes|shipments|packages)?|as soon as (it is|they are|it's) ready|ship what you have|don'?t wait|no need to wait|whatever is ready)");
    private static final Pattern PICKUP = Pattern.compile("(?i)\\b(pick ?up|pick (it|this|them) up|collect (it|this|from|in)|in.store collection)");
    private static final Pattern GIFT = Pattern.compile("(?i)\\b(gift|present for|birthday|anniversary|no receipt|without (the |a )?receipt|gift.?wrap|gift (note|message|card)|surprise)");

    private IntentDetector() {}

    /** Deterministic keyword detection over the free-text note (no model). Precedence: pickup, together, urgent, separate. */
    public static DetectedIntent fromKeywords(String note) {
        if (note == null || note.isBlank()) return DetectedIntent.none();
        String n = note.toLowerCase(Locale.ROOT);
        boolean gift = GIFT.matcher(n).find();
        boolean pickup = PICKUP.matcher(n).find();
        boolean together = TOGETHER.matcher(n).find();
        boolean urgent = URGENT.matcher(n).find();
        boolean separate = SEPARATE.matcher(n).find();
        String policy = null, label = gift ? "gift" : "none";
        Boolean splitOk = null;
        if (pickup) { policy = Policies.PICKUP_CONSOLIDATED; label = "pickup"; splitOk = false; }
        else if (together) { policy = Policies.SHIP_COMPLETE; label = "together"; splitOk = false; }
        else if (urgent) { policy = Policies.EARLIEST_PARTIAL; label = "urgent"; splitOk = true; }
        else if (separate) { policy = Policies.SHIP_AS_AVAILABLE; label = "separate"; splitOk = true; }
        if (policy == null && !gift) return DetectedIntent.none();
        return new DetectedIntent(policy, gift, urgent && !pickup && !together, together, pickup, splitOk, "keywords", policy == null ? 0.5 : 0.7, null, null, label);
    }

    /** Label for a decision outcome (policy + gift flag) using the same vocabulary as the keyword path. */
    public static String label(String policy, boolean gift) {
        if (policy == null) return gift ? "gift" : "none";
        return switch (policy) {
            case Policies.PICKUP_CONSOLIDATED -> "pickup";
            case Policies.SHIP_COMPLETE -> "together";
            case Policies.EARLIEST_PARTIAL -> "urgent";
            case Policies.SHIP_AS_AVAILABLE -> "separate";
            default -> gift ? "gift" : "none";
        };
    }

    public static final List<String> LABELS = List.of("gift", "urgent", "together", "pickup", "separate");
}
