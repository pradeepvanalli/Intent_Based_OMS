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
 * FS-02 on the live path: for {@code sourcing_node_score}, each candidate's {@code reject_rate} becomes the learned
 * Beta-binomial posterior and {@code on_time_rate} is added, once the node has at least 10 observations and the
 * parameter family is promoted for live use. Below that, or when the node-reliability learner is off, paused or at L0,
 * the candidate keeps its static values, so a new tenant behaves exactly as before any learning.
 */
@Component
public class NodeReliabilityFeatures implements FeatureContributor {
    public static final String DECISION = "sourcing_node_score";
    public static final String REJECT_RATE = "sourcing.node_reject_rate";
    public static final String ON_TIME_RATE = "sourcing.node_on_time_rate";
    private static final int MIN_SAMPLES = 10;

    private final LearnedFactCache facts;
    private final ParameterResolver resolver;

    public NodeReliabilityFeatures(LearnedFactCache facts, ParameterResolver resolver) {
        this.facts = facts;
        this.resolver = resolver;
    }

    @Override public boolean appliesTo(String decision) { return DECISION.equals(decision); }

    @Override
    @SuppressWarnings("unchecked")
    public void contribute(String decision, Map<String, Object> input, DecisionOptions opts, List<Map<String, Object>> factsUsed) {
        if (!(input.get("candidates") instanceof List<?> cands)) return;
        for (Object o : cands) {
            if (!(o instanceof Map<?, ?> m) || m.get("id") == null) continue;
            Map<String, Object> cand = (Map<String, Object>) m;
            String nodeId = String.valueOf(cand.get("id"));
            Double staticReject = cand.get("reject_rate") instanceof Number n ? n.doubleValue() : null;
            apply(cand, "reject_rate", REJECT_RATE, staticReject, nodeId, FactKey.NODE_REJECT_RATE, factsUsed);
            apply(cand, "on_time_rate", ON_TIME_RATE, null, nodeId, FactKey.NODE_ON_TIME_RATE, factsUsed);
        }
    }

    private void apply(Map<String, Object> cand, String field, String parameter, Double staticValue, String nodeId, FactKey key, List<Map<String, Object>> used) {
        Optional<LearnedFact> fact = facts.active(SubjectType.NODE, nodeId, key)
            .filter(f -> f.sampleSize() >= MIN_SAMPLES && f.value() instanceof TypedValue.NumberValue);
        if (fact.isEmpty()) return;
        double learned = ((TypedValue.NumberValue) fact.get().value()).value();
        ParameterResolver.Resolution r = resolver.explain(parameter, staticValue, learned);
        if (r.learnedServed()) cand.put(field, r.value());
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("subjectType", SubjectType.NODE.name());
        u.put("subjectId", nodeId);
        u.put("factKey", key.name());
        u.put("version", fact.get().version());
        u.put("learned", learned);
        u.put("served", r.value());
        u.put("source", r.source());
        u.put("reason", r.reason());
        used.add(u);
    }
}
