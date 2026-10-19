package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Customer-safe explanation (FR-XAI-04, XP-05). Built from an <b>allowlist</b>, never by redacting the full trace: a plain
 * summary of what happened, the outcome in words when it is a customer-meaningful option (never a fulfilment location,
 * a score or a risk verdict), reasons as sentences, the plain-language questions of non-internal decisions, and
 * {@code learnedContext} sentences naming which kind of learned knowledge shaped the decision. No state, no node or
 * candidate ids, no rates, scores, costs, margins, backlog, staffing, distances, providers, probabilities or learned
 * values ever leave in this view. {@code state} is always empty and kept only for response-shape compatibility.
 */
public record CustomerSafeTraceView(String recordId, String decision, int version, Object value, Band band, String action, Layer layer, boolean requiresHuman,
                                    List<String> reasonCodes, List<Question> questions, Map<String, Object> state, Instant createdAt,
                                    List<String> learnedContext, String summary, List<String> reasons) {
    /** Anything whose name matches this is internal economics, risk or operations data and is never shown. */
    private static final Pattern INTERNAL = Pattern.compile("(?i)(margin|cost|risk|fraud|abuse|pnl|cogs|penalt|score|landed|labou?r|velocity|limit|linkage|reputation|dispute|"
        + "device|token|address|rate|backlog|staffing|distance|node|candidate|rank|probab|confidence|calibrat|propensity|chargeback|rto|breach)");

    /** Learned fact keys that may be mentioned to a customer, with the wording to use. Anything else is withheld. */
    private static final Map<String, String> SAFE_FACTS = Map.ofEntries(
        Map.entry("NODE_ON_TIME_RATE", "how reliably each fulfilment location has shipped on time recently"),
        Map.entry("NODE_REJECT_RATE", "how often each fulfilment location has been able to fill orders"),
        Map.entry("LANE_TRANSIT_DAYS_P50", "how long deliveries on this route have actually taken"),
        Map.entry("LANE_TRANSIT_DAYS_P90", "how long deliveries on this route have actually taken"),
        Map.entry("LANE_ON_TIME_RATE", "how often deliveries on this route have arrived on time"),
        Map.entry("LANE_CALIBRATION_OFFSET", "how often deliveries on this route have arrived on time"),
        Map.entry("SPLIT_TOLERANCE", "your past preferences about receiving an order in more than one package"),
        Map.entry("SPEED_VS_COST", "your past choices between faster and cheaper delivery"),
        Map.entry("PICKUP_PREFERENCE", "whether you have preferred store pickup before"),
        Map.entry("PREFERRED_CONTACT_CHANNEL", "the way you prefer us to contact you"),
        Map.entry("PROMISE_BUFFER_DAYS", "how accurate our delivery promises have been"),
        Map.entry("STOCK_AGE_DAYS", "where stock is available"),
        Map.entry("INBOUND_RELIABILITY", "when new stock is expected to arrive"));

    /** Reason codes with customer wording; others are shown humanised only when they are not internal. */
    private static final Map<String, String> REASON_WORDING = Map.of(
        "low_confidence", "The system was not sure enough to decide on its own, so a safe standard choice or a person took over.",
        "confirm_default_applied", "A standard choice was applied while the details are confirmed.",
        "escape_option", "The request did not match a standard option, so a person is looking at it.",
        "precheck_failed", "A required check was not met, so this waits for a person.",
        "postcheck_failed", "The proposed answer did not pass a final check, so a standard choice was used.");

    public record Question(String key, String instructions, Object answer) {}

    public static CustomerSafeTraceView from(DecisionTrace t) {
        Map<String, Object> details = t.details() == null ? Map.of() : t.details();
        boolean internalDecision = INTERNAL.matcher(t.decision()).find();
        boolean ranking = details.containsKey("topK");
        Object mainKey = details.get("mainQuestion");
        DecisionTrace.QuestionTrace main = t.questions().stream().filter(q -> q.key().equals(String.valueOf(mainKey))).findFirst()
            .orElse(t.questions().isEmpty() ? null : t.questions().get(0));
        Object raw = t.override() != null && t.override().get("value") != null ? t.override().get("value") : t.value();
        Object value = internalDecision || ranking ? null : safeValue(main, raw);

        List<Question> qs = internalDecision || ranking ? List.of() : t.questions().stream()
            .filter(q -> "choice".equals(q.type()) || "noul".equals(q.type()))
            .filter(q -> !INTERNAL.matcher(q.key()).find() && !INTERNAL.matcher(q.instructions() == null ? "" : q.instructions()).find())
            .map(q -> new Question(q.key(), q.instructions(), q.answer() == null ? null : safeValue(q, q.answer().value())))
            .toList();

        List<String> codes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (String code : t.reasonCodes() == null ? List.<String>of() : t.reasonCodes()) {
            if (REASON_WORDING.containsKey(code)) { codes.add(code); reasons.add(REASON_WORDING.get(code)); }
            else if (!internalDecision && !INTERNAL.matcher(code).find()) { codes.add(code); reasons.add(humanise(code) + "."); }
        }
        return new CustomerSafeTraceView(t.recordId(), t.decision(), t.version(), value, t.band(), t.action(), t.layer(), t.requiresHuman(),
            codes, qs, Map.of(), t.createdAt(), learnedContext(t.learning()), summary(t.action(), t.requiresHuman()), reasons);
    }

    /** The answer when it is one of the question's own enumerated options or a yes/no; otherwise withheld. */
    private static Object safeValue(DecisionTrace.QuestionTrace q, Object v) {
        if (q == null || v == null) return null;
        if ("noul".equals(q.type()) && v instanceof Boolean) return v;
        if ("choice".equals(q.type()) && q.choiceCriteria() != null && q.choiceCriteria().containsKey(String.valueOf(v)) && !INTERNAL.matcher(String.valueOf(v)).find()) return v;
        return null;
    }

    private static String summary(String action, boolean requiresHuman) {
        if (requiresHuman || "escalate".equals(action)) return "A member of our team is reviewing this.";
        return switch (action == null ? "" : action) {
            case "act" -> "Our order system decided this automatically.";
            case "confirm" -> "Our order system applied its standard choice and may confirm the details with you.";
            case "blocked" -> "This is on hold until a member of our team reviews it.";
            default -> "Our order system applied its standard choice.";
        };
    }

    private static String humanise(String code) {
        String s = code.replace('_', ' ').replace('-', ' ').trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Plain-language mentions of the learned knowledge that was actually served to this decision. */
    static List<String> learnedContext(Map<String, Object> learning) {
        if (learning == null || !(learning.get("learnedFacts") instanceof List<?> facts)) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object f : facts) {
            if (!(f instanceof Map<?, ?> m) || !"LEARNED".equals(m.get("source"))) continue;
            String phrase = SAFE_FACTS.get(String.valueOf(m.get("factKey")));
            if (phrase != null) out.add("This decision took into account " + phrase + ".");
        }
        return new ArrayList<>(out);
    }
}
