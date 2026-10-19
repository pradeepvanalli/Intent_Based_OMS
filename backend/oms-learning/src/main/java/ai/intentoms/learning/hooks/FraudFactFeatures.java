package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.FraudSignalLearner;
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
 * FD-01 / FD-04 on the live path for {@code payment_risk}. Each raw signal the workflow computed (card velocity,
 * address / device / card linkage) is joined with what that level has meant for this tenant
 * ({@code FRAUD_SIGNAL_RATE}, learned by {@link FraudSignalLearner}): {@code payment.learned_fraud_rates.<signal>} and
 * the highest of them as {@code payment.learned_fraud_rate}. The agent's mandate history becomes
 * {@code agent.history_fraud_rate} and {@code agent.history_orders}. The address-reuse count of a ring the abuse
 * scanner found is {@code payment.address_reuse_count}. Each reaches the decision only while its family
 * ({@code fraud.signal_risk}, {@code fraud.address_reuse_count}) is promoted for live use and its learner is above L0;
 * every fact consulted is listed on the decision record. The agent reputation score (FD-05) is deliberately not here:
 * it feeds the manual trust-tier review only.
 */
@Component
public class FraudFactFeatures implements FeatureContributor {
    public static final String DECISION = "payment_risk";
    public static final String SIGNAL_RISK = "fraud.signal_risk";
    public static final String ADDRESS_REUSE = "fraud.address_reuse_count";
    static final int MIN_AGENT_ORDERS = 5;

    private final LearnedFactCache facts;
    private final ParameterResolver resolver;

    public FraudFactFeatures(LearnedFactCache facts, ParameterResolver resolver) {
        this.facts = facts;
        this.resolver = resolver;
    }

    @Override public boolean appliesTo(String decision) { return DECISION.equals(decision); }

    @Override
    @SuppressWarnings("unchecked")
    public void contribute(String decision, Map<String, Object> input, DecisionOptions opts, List<Map<String, Object>> factsUsed) {
        if (input.get("payment") instanceof Map<?, ?> pm) {
            Map<String, Object> payment = (Map<String, Object>) pm;
            Map<String, Object> rates = new LinkedHashMap<>();
            for (String signal : FraudSignalLearner.SIGNALS) {
                if (!(payment.get(signal) instanceof Number count)) continue;
                String subject = FraudSignalLearner.subject(signal, FraudSignalLearner.level(count.doubleValue()));
                serve(SubjectType.DECISION, subject, FactKey.FRAUD_SIGNAL_RATE, SIGNAL_RISK, 1, factsUsed).ifPresent(v -> rates.put(signal, v));
            }
            if (!rates.isEmpty()) {
                payment.put("learned_fraud_rates", rates);
                payment.put("learned_fraud_rate", rates.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).max().orElse(0));
            }
            if (input.get("address") instanceof Map<?, ?> addr && addr.get("token") != null) {
                serve(SubjectType.ADDRESS, String.valueOf(addr.get("token")), FactKey.ADDRESS_REUSE_COUNT, ADDRESS_REUSE, 0, factsUsed)
                    .ifPresent(v -> payment.put("address_reuse_count", (int) Math.round(v)));
            }
        }
        if (input.get("agent") instanceof Map<?, ?> a && a.get("id") != null) {
            Map<String, Object> agent = (Map<String, Object>) a;
            String agentId = String.valueOf(a.get("id"));
            Optional<LearnedFact> history = facts.active(SubjectType.AGENT, agentId, FactKey.FRAUD_SIGNAL_RATE);
            serve(SubjectType.AGENT, agentId, FactKey.FRAUD_SIGNAL_RATE, SIGNAL_RISK, MIN_AGENT_ORDERS, factsUsed).ifPresent(v -> {
                agent.put("history_fraud_rate", v);
                agent.put("history_orders", history.map(LearnedFact::sampleSize).orElse(0));
            });
        }
    }

    private Optional<Double> serve(SubjectType st, String subjectId, FactKey key, String parameter, int minSamples, List<Map<String, Object>> used) {
        Optional<LearnedFact> fact = facts.active(st, subjectId, key).filter(f -> f.value() instanceof TypedValue.NumberValue && f.sampleSize() >= minSamples);
        if (fact.isEmpty()) return Optional.empty();
        double learned = ((TypedValue.NumberValue) fact.get().value()).value();
        ParameterResolver.Resolution r = resolver.explain(parameter, null, learned);
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("subjectType", st.name());
        u.put("subjectId", subjectId);
        u.put("factKey", key.name());
        u.put("version", fact.get().version());
        u.put("learned", learned);
        u.put("served", r.value());
        u.put("source", r.source());
        u.put("reason", r.reason());
        used.add(u);
        return r.learnedServed() ? Optional.of(r.value()) : Optional.empty();
    }
}
