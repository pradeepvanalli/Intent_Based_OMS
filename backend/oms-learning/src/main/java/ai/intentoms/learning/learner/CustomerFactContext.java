package ai.intentoms.learning.learner;

import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CI-05: merges a customer's and their segment's active learned facts into a decision's typed input/state map as
 * {@code customer.facts.&lt;FACT_KEY&gt;} entries — never raw order/return history, only the already-typed fact
 * values. {@code DecisionRuntime} takes a plain {@code Map<String, Object>} input built by the caller (there's no
 * separate "DecisionContext" builder stage to hook into), so the clean, safe extension point is here: a caller like
 * {@code IntentService} or sourcing calls {@link #merge} on its own input map right before invoking
 * {@code DecisionService.decide(...)}, rather than this module reaching into the decision runtime itself.
 */
@Component
public class CustomerFactContext {
    private final LearnedFactRepository facts;
    public CustomerFactContext(LearnedFactRepository facts) { this.facts = facts; }

    public Map<String, Object> merge(Map<String, Object> input, String customerRef) {
        if (customerRef == null || customerRef.isBlank()) return input;
        Map<String, Object> out = new LinkedHashMap<>(input == null ? Map.of() : input);
        for (LearnedFact f : facts.activeForSubject(SubjectType.CUSTOMER, customerRef)) {
            out.put("customer.facts." + f.factKey().name(), scalar(f.value()));
        }
        return out;
    }

    private static Object scalar(TypedValue v) {
        return switch (v) {
            case TypedValue.NumberValue n -> n.value();
            case TypedValue.BoolValue b -> b.value();
            case TypedValue.TextValue t -> t.value();
            case TypedValue.ListValue l -> l.values();
        };
    }
}
