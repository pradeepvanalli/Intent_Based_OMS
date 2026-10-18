package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.CustomerFactContext;
import ai.intentoms.learning.learner.CustomerFeatureFamilies;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.CustomerEvidenceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CI-05 on the live path: for decisions that read customer preferences (default {@code split_acceptability} and
 * {@code fulfilment_intent}, see {@code oms.learning.customer-features.decisions}), adds {@code customer.facts.*} to the
 * input while the {@link CustomerFeatureFamilies#PREFERENCES} family is promoted for live use and its learners are on.
 * The customer is the input's {@code customer.ref} / {@code customerRef}, else the order the decision is about. Every
 * fact found is listed on the decision record with whether it was served, so the Why view shows what the decision knew.
 */
@Component
public class CustomerFeatures implements FeatureContributor {
    private final CustomerFactContext context;
    private final CustomerEvidenceRepository evidence;
    private final ParameterResolver resolver;
    private final Set<String> decisions;

    public CustomerFeatures(CustomerFactContext context, CustomerEvidenceRepository evidence, ParameterResolver resolver,
                            @Value("${oms.learning.customer-features.decisions:split_acceptability,fulfilment_intent}") List<String> decisions) {
        this.context = context;
        this.evidence = evidence;
        this.resolver = resolver;
        this.decisions = Set.copyOf(decisions);
    }

    @Override public boolean appliesTo(String decision) { return decisions.contains(decision); }

    @Override
    @SuppressWarnings("unchecked")
    public void contribute(String decision, Map<String, Object> input, DecisionOptions opts, List<Map<String, Object>> factsUsed) {
        String customerRef = customerRef(input, opts);
        if (customerRef == null) return;
        Map<FactKey, LearnedFact> found = context.facts(customerRef);
        if (found.isEmpty()) return;
        boolean live = resolver.isLive(CustomerFeatureFamilies.PREFERENCES);
        Map<String, Object> served = new LinkedHashMap<>();
        for (var e : found.entrySet()) {
            LearnedFact f = e.getValue();
            Object value = CustomerFactContext.scalar(f.value());
            Object servedValue = null;
            String reason;
            if (f.value() instanceof TypedValue.NumberValue n) {
                ParameterResolver.Resolution r = resolver.explain(CustomerFeatureFamilies.PREFERENCES, null, n.value());
                servedValue = r.learnedServed() ? r.value() : null;
                reason = r.reason();
            } else {
                servedValue = live ? value : null;
                reason = live ? "promoted for live use" : "not promoted for live use";
            }
            if (servedValue != null) served.put(CustomerFactContext.field(e.getKey()), servedValue);
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("subjectType", "CUSTOMER");
            u.put("subjectId", customerRef);
            u.put("factKey", e.getKey().name());
            u.put("version", f.version());
            u.put("learned", value);
            u.put("served", servedValue);
            u.put("source", servedValue != null ? "LEARNED" : "STATIC");
            u.put("reason", reason);
            factsUsed.add(u);
        }
        if (served.isEmpty()) return;
        Map<String, Object> customer = input.get("customer") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        customer.put("facts", served);
        input.put("customer", customer);
    }

    @SuppressWarnings("unchecked")
    private String customerRef(Map<String, Object> input, DecisionOptions opts) {
        if (input.get("customer") instanceof Map<?, ?> c && c.get("ref") != null) return String.valueOf(c.get("ref"));
        if (input.get("customerRef") != null) return String.valueOf(input.get("customerRef"));
        if ("customer".equals(opts.subjectType()) && opts.subjectId() != null) return opts.subjectId();
        if ("order".equals(opts.subjectType()) && opts.subjectId() != null) return evidence.customerOfOrder(opts.subjectId()).orElse(null);
        return null;
    }
}
