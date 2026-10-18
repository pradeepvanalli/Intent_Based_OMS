package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.BacklogBreachCurve;
import ai.intentoms.learning.learner.NodeReliabilityLearner;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FS-02 / FS-05 on the live path: for {@code sourcing_node_score}, each candidate's {@code reject_rate} becomes the
 * learned Beta-binomial posterior, {@code on_time_rate} is added, and {@code breach_probability} is read off the
 * node's backlog-to-breach curve at its current backlog. Reliability uses the most specific fact with at least 10
 * observations: the node for this order's SKU class in the current day part, then for the SKU class, then overall.
 * Every value reaches the decision only when its parameter family is promoted for live use and its learner is on;
 * otherwise the candidate keeps its static values, so a new tenant behaves exactly as before any learning. Every fact
 * consulted is listed on the decision record, whether or not it was served.
 */
@Component
public class NodeReliabilityFeatures implements FeatureContributor {
    public static final String DECISION = "sourcing_node_score";
    public static final String REJECT_RATE = "sourcing.node_reject_rate";
    public static final String ON_TIME_RATE = "sourcing.node_on_time_rate";
    public static final String BACKLOG_BREACH = "sourcing.node_backlog_breach";
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
        Map<String, Object> order = input.get("order") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String skuClass = order.get("sku_class") == null ? null : String.valueOf(order.get("sku_class"));
        String dayPart = order.get("day_part") == null ? NodeReliabilityLearner.dayPart(Instant.now()) : String.valueOf(order.get("day_part"));
        for (Object o : cands) {
            if (!(o instanceof Map<?, ?> cm) || cm.get("id") == null) continue;
            Map<String, Object> cand = (Map<String, Object>) cm;
            String nodeId = String.valueOf(cand.get("id"));
            Double staticReject = cand.get("reject_rate") instanceof Number n ? n.doubleValue() : null;
            apply(cand, "reject_rate", REJECT_RATE, staticReject, mostSpecific(nodeId, skuClass, dayPart, FactKey.NODE_REJECT_RATE), FactKey.NODE_REJECT_RATE, factsUsed);
            apply(cand, "on_time_rate", ON_TIME_RATE, null, mostSpecific(nodeId, skuClass, dayPart, FactKey.NODE_ON_TIME_RATE), FactKey.NODE_ON_TIME_RATE, factsUsed);
            breach(cand, nodeId, factsUsed);
        }
    }

    private Optional<LearnedFact> mostSpecific(String nodeId, String skuClass, String dayPart, FactKey key) {
        if (skuClass != null) {
            Optional<LearnedFact> f = usable(NodeReliabilityLearner.subject(nodeId, skuClass, dayPart), key);
            if (f.isPresent()) return f;
            f = usable(NodeReliabilityLearner.subject(nodeId, skuClass, null), key);
            if (f.isPresent()) return f;
        }
        return usable(nodeId, key);
    }

    private Optional<LearnedFact> usable(String subjectId, FactKey key) {
        return facts.active(SubjectType.NODE, subjectId, key).filter(f -> f.sampleSize() >= MIN_SAMPLES && f.value() instanceof TypedValue.NumberValue);
    }

    private void apply(Map<String, Object> cand, String field, String parameter, Double staticValue, Optional<LearnedFact> fact, FactKey key, List<Map<String, Object>> used) {
        if (fact.isEmpty()) return;
        double learned = ((TypedValue.NumberValue) fact.get().value()).value();
        ParameterResolver.Resolution r = resolver.explain(parameter, staticValue, learned);
        if (r.learnedServed()) cand.put(field, r.value());
        used.add(entry(fact.get(), key, learned, r));
    }

    private void breach(Map<String, Object> cand, String nodeId, List<Map<String, Object>> used) {
        Optional<LearnedFact> curveFact = facts.active(SubjectType.NODE, nodeId, FactKey.NODE_BACKLOG_BREACH_CURVE);
        if (curveFact.isEmpty() || !(cand.get("backlog_units") instanceof Number backlog)) return;
        Optional<Double> p = BacklogBreachCurve.at(BacklogBreachCurve.parse(curveFact.get()), backlog.doubleValue());
        if (p.isEmpty()) return;
        ParameterResolver.Resolution r = resolver.explain(BACKLOG_BREACH, null, p.get());
        if (r.learnedServed()) cand.put("breach_probability", r.value());
        used.add(entry(curveFact.get(), FactKey.NODE_BACKLOG_BREACH_CURVE, p.get(), r));
    }

    private static Map<String, Object> entry(LearnedFact fact, FactKey key, double learned, ParameterResolver.Resolution r) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("subjectType", SubjectType.NODE.name());
        u.put("subjectId", fact.subjectId());
        u.put("factKey", key.name());
        u.put("version", fact.version());
        u.put("learned", learned);
        u.put("served", r.value());
        u.put("source", r.source());
        u.put("reason", r.reason());
        return u;
    }
}
