package ai.intentoms.learning.learner;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CI-05: a customer's learned facts in the typed shape decisions read: {@code customer.facts.<key>} with lower-case keys
 * ({@code split_tolerance}, {@code speed_vs_cost}, {@code pickup_preference}, {@code preferred_contact_channel},
 * {@code segment}). Only already-typed fact values, never raw order or return history. Reads go through the learned-fact
 * cache, so a live decision never waits on the database for them.
 */
@Component
public class CustomerFactContext {
    /** The facts a decision may see, in the order they are listed on the decision record. */
    public static final List<FactKey> DECISION_FACTS = List.of(FactKey.SPLIT_TOLERANCE, FactKey.SPEED_VS_COST, FactKey.PICKUP_PREFERENCE,
        FactKey.PREFERRED_CONTACT_CHANNEL, FactKey.SEGMENT_MEMBERSHIP);

    private final LearnedFactCache facts;
    private final ai.intentoms.learning.store.LearnedFactRepository repository;

    public CustomerFactContext(LearnedFactCache facts, ai.intentoms.learning.store.LearnedFactRepository repository) {
        this.facts = facts;
        this.repository = repository;
    }

    /** Same as {@link #facts} but read from the database, for people-facing views that must not lag the learner. */
    public Map<FactKey, LearnedFact> freshFacts(String customerRef) {
        Map<FactKey, LearnedFact> out = new LinkedHashMap<>();
        if (customerRef == null || customerRef.isBlank()) return out;
        for (FactKey k : DECISION_FACTS) repository.active(SubjectType.CUSTOMER, customerRef, k).ifPresent(f -> out.put(k, f));
        return out;
    }

    /** Field name under {@code customer.facts} for a fact key. */
    public static String field(FactKey key) {
        return key == FactKey.SEGMENT_MEMBERSHIP ? "segment" : key.name().toLowerCase(Locale.ROOT);
    }

    /** Active facts of one customer, by fact key (absent keys omitted). */
    public Map<FactKey, LearnedFact> facts(String customerRef) {
        Map<FactKey, LearnedFact> out = new LinkedHashMap<>();
        if (customerRef == null || customerRef.isBlank()) return out;
        for (FactKey k : DECISION_FACTS) facts.active(SubjectType.CUSTOMER, customerRef, k).ifPresent(f -> out.put(k, f));
        return out;
    }

    /** A copy of {@code input} with {@code customer.facts} filled from every active fact (no live-use gating; see CustomerFeatures). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(Map<String, Object> input, String customerRef) {
        Map<String, Object> out = new LinkedHashMap<>(input == null ? Map.of() : input);
        Map<FactKey, LearnedFact> found = facts(customerRef);
        if (found.isEmpty()) return out;
        Map<String, Object> customer = out.get("customer") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        Map<String, Object> f = new LinkedHashMap<>();
        found.forEach((k, v) -> f.put(field(k), scalar(v.value())));
        customer.put("facts", f);
        out.put("customer", customer);
        return out;
    }

    public static Object scalar(TypedValue v) {
        return switch (v) {
            case TypedValue.NumberValue n -> n.value();
            case TypedValue.BoolValue b -> b.value();
            case TypedValue.TextValue t -> t.value();
            case TypedValue.ListValue l -> l.values();
        };
    }
}
