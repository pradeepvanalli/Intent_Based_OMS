package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IU-06 on the live path: {@code backorder_next_action} sees how backorders have ended. {@code line.backorder_conversion_rate}
 * is how often customers kept a backorder of this SKU instead of cancelling ({@code BACKORDER_CONVERSION_RATE}, subject
 * SKU); {@code history.option_kept_rate.<option>} is how often lines were kept after the decision chose that option
 * (subject DECISION {@code backorder_next_action|<option>}), so waiting that usually ends in a cancellation shows up
 * against re-sourcing or a substitute. Both reach the decision only while {@code inventory.backorder_conversion} is
 * promoted for live use and its learner is above L0; every fact consulted is listed on the decision record.
 * {@code history.option_rank} (IU-06) is {@link #OPTIONS} re-ranked by that same served rate, best-converting
 * first — never adding or removing an option, just changing the order the decision's own enumerated options are
 * recorded in, so "tune the options" is observable on the decision record and not only a number fed to the model.
 */
@Component
public class BackorderFeatures implements FeatureContributor {
    public static final String DECISION = "backorder_next_action";
    public static final String PARAMETER = "inventory.backorder_conversion";
    /** The options tenants' backorder decisions offer (the demo set and the electronics set); "unclear" is never an option to learn. */
    public static final List<String> OPTIONS = List.of("wait", "resource", "cancel_line", "offer_substitute", "substitute", "cancel", "split");
    static final int MIN_SAMPLES = 5;

    private final LearnedFactCache facts;
    private final ParameterResolver resolver;

    public BackorderFeatures(LearnedFactCache facts, ParameterResolver resolver) {
        this.facts = facts;
        this.resolver = resolver;
    }

    public static String optionSubject(String option) { return DECISION + "|" + option; }

    @Override public boolean appliesTo(String decision) { return DECISION.equals(decision); }

    @Override
    @SuppressWarnings("unchecked")
    public void contribute(String decision, Map<String, Object> input, DecisionOptions opts, List<Map<String, Object>> factsUsed) {
        if (input.get("line") instanceof Map<?, ?> l && l.get("sku") != null && !String.valueOf(l.get("sku")).isBlank()) {
            Map<String, Object> line = (Map<String, Object>) l;
            serve(SubjectType.SKU, String.valueOf(line.get("sku")), factsUsed).ifPresent(v -> line.put("backorder_conversion_rate", v));
        }
        Map<String, Double> byOption = new LinkedHashMap<>();
        for (String option : OPTIONS) serve(SubjectType.DECISION, optionSubject(option), factsUsed).ifPresent(v -> byOption.put(option, v));
        Map<String, Object> history = input.get("history") instanceof Map<?, ?> h ? (Map<String, Object>) h : new LinkedHashMap<>();
        if (!byOption.isEmpty()) history.put("option_kept_rate", byOption);
        // IU-06: "tune the options" means more than feeding a learned number into the input — this never changes
        // which options are offered (never removes or adds one, strictly a re-rank), but the decision's own
        // enumerated option order (persisted on the decision record, read by the "why" trace and the Changes UI)
        // now reflects what customers actually choose, instead of OPTIONS' fixed Java declaration order that
        // nothing downstream ever read. An untouched SKU (no learned rate for any option) gets that same static,
        // declared order back unchanged.
        history.put("option_rank", rankedOptions(byOption));
        input.put("history", history);
    }

    /**
     * {@link #OPTIONS}, re-ranked best-converting first by the rate actually served (never removed, never a new
     * option invented). An option with no learned rate yet keeps its place relative to the other un-learned
     * options, after every ranked one — so with nothing learned at all the order is simply {@link #OPTIONS} itself.
     */
    static List<String> rankedOptions(Map<String, Double> byOption) {
        List<String> ranked = new java.util.ArrayList<>(OPTIONS);
        ranked.sort((a, b) -> {
            Double ra = byOption.get(a), rb = byOption.get(b);
            if (ra == null && rb == null) return 0;         // both unlearned: stable sort keeps OPTIONS' order
            if (ra == null) return 1;                        // unlearned sinks below any learned rate
            if (rb == null) return -1;
            return Double.compare(rb, ra);                   // higher conversion first
        });
        return ranked;
    }

    private Optional<Double> serve(SubjectType st, String subjectId, List<Map<String, Object>> used) {
        Optional<LearnedFact> fact = facts.active(st, subjectId, FactKey.BACKORDER_CONVERSION_RATE)
            .filter(f -> f.sampleSize() >= MIN_SAMPLES && f.value() instanceof TypedValue.NumberValue);
        if (fact.isEmpty()) return Optional.empty();
        double learned = ((TypedValue.NumberValue) fact.get().value()).value();
        ParameterResolver.Resolution r = resolver.explain(PARAMETER, null, learned);
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("subjectType", st.name());
        u.put("subjectId", subjectId);
        u.put("factKey", FactKey.BACKORDER_CONVERSION_RATE.name());
        u.put("version", fact.get().version());
        u.put("learned", learned);
        u.put("served", r.value());
        u.put("source", r.source());
        u.put("reason", r.reason());
        used.add(u);
        return r.learnedServed() ? Optional.of(Math.round(r.value() * 1000) / 1000.0) : Optional.empty();
    }
}
