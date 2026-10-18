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
 * FD-01 / FD-04 / FD-05 on the live path: adds learned fraud facts to a {@code payment_risk} decision's input. The
 * caller (the order workflow) supplies the raw signals it can count itself plus the agent id and address token; this
 * adds what only the learners know:
 * <ul>
 *   <li>{@code agent.reputation_score} and {@code agent.dispute_rate} from the agent-reputation learner (FD-05);</li>
 *   <li>{@code payment.address_reuse_count}, distinct customers on this address over 30 days, from the abuse-pattern
 *       finder's ring detection (FD-04).</li>
 * </ul>
 * Each family reaches the decision only while promoted for live use and its learner is on and above L0
 * ({@link ParameterResolver}); otherwise the fact is listed as considered but not used, and the decision sees the input
 * exactly as the caller built it.
 */
@Component
public class FraudFactFeatures implements FeatureContributor {
    public static final String DECISION = "payment_risk";
    public static final String AGENT_REPUTATION = "fraud.agent_reputation";
    public static final String AGENT_DISPUTE_RATE = "fraud.agent_dispute_rate";
    public static final String ADDRESS_REUSE = "fraud.address_reuse_count";

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
        if (input.get("agent") instanceof Map<?, ?> a && a.get("id") != null) {
            Map<String, Object> agent = (Map<String, Object>) a;
            String agentId = String.valueOf(a.get("id"));
            apply(agent, "reputation_score", AGENT_REPUTATION, SubjectType.AGENT, agentId, FactKey.AGENT_REPUTATION, factsUsed);
            apply(agent, "dispute_rate", AGENT_DISPUTE_RATE, SubjectType.AGENT, agentId, FactKey.DISPUTE_RATE, factsUsed);
        }
        if (input.get("address") instanceof Map<?, ?> addr && addr.get("token") != null && input.get("payment") instanceof Map<?, ?> pay) {
            apply((Map<String, Object>) pay, "address_reuse_count", ADDRESS_REUSE, SubjectType.ADDRESS, String.valueOf(addr.get("token")), FactKey.ADDRESS_REUSE_COUNT, factsUsed);
        }
    }

    private void apply(Map<String, Object> target, String field, String parameter, SubjectType st, String subjectId, FactKey key, List<Map<String, Object>> used) {
        Optional<LearnedFact> fact = facts.active(st, subjectId, key).filter(f -> f.value() instanceof TypedValue.NumberValue);
        if (fact.isEmpty()) return;
        double learned = ((TypedValue.NumberValue) fact.get().value()).value();
        ParameterResolver.Resolution r = resolver.explain(parameter, null, learned);
        if (r.learnedServed()) target.put(field, key == FactKey.ADDRESS_REUSE_COUNT ? (Object) (int) Math.round(r.value()) : r.value());
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
    }
}
