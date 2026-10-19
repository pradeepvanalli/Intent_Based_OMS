package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * EX-01 / requirements §11: the governed exploration floor. Off-policy estimates are only as good as the logged
 * propensities: a decision that always applies its own choice logs propensity 1 and gives IPS nothing to correct. A
 * tenant can therefore let a T0 decision explore: on {@code learning.exploration.<decision>} of its decisions (default
 * 0, capped at {@value #MAX_SHARE}) another allowed arm is applied, chosen uniformly, and logged with its true
 * probability (share / other arms); every other decision logs 1 - share for its normal choice.
 *
 * <p>Governed like every learned behaviour: only T0 decisions (the lowest stakes, which act on their own), never a
 * decision about refunds, cancellations, pricing, credit or fraud, never for a holdout customer (EX-04 keeps them on
 * the baseline), and off whenever the tenant's or the global learning kill switch is on.
 */
@Component
public class ExplorationFloor {
    public static final String KEY_PREFIX = "learning.exploration.";
    public static final double MAX_SHARE = 0.10;
    /** Words that keep a decision out of exploration whatever the configuration says. */
    static final List<String> NEVER = List.of("refund", "cancel", "pric", "credit", "appeasement", "fraud", "payment_risk", "rto_risk", "return_risk", "address_risk", "mandate", "injection", "screen");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantConfigStore config;
    private final LearnerGovernance governance;
    private final ParameterResolver parameters;

    public ExplorationFloor(TenantConfigStore config, LearnerGovernance governance, ParameterResolver parameters) {
        this.config = config;
        this.governance = governance;
        this.parameters = parameters;
    }

    /** Whether the decision may ever explore: T0 and nothing that moves money or judges fraud. */
    public static boolean allowed(String decision, Tier tier) {
        if (tier != Tier.T0 || decision == null) return false;
        String d = decision.toLowerCase(Locale.ROOT);
        return NEVER.stream().noneMatch(d::contains);
    }

    /** The configured share for the decision, clamped to [0, {@value #MAX_SHARE}]; 0 when it may not explore. */
    public double share(String decision, Tier tier) {
        if (!allowed(decision, tier)) return 0;
        try { return Math.max(0.0, Math.min(MAX_SHARE, config.getDouble(KEY_PREFIX + decision, 0.0))); }
        catch (RuntimeException e) { return 0; }
    }

    public Optional<DecisionLearningHooks.Exploration> explore(String decision, Tier tier, List<String> arms, String chosen, String routingKey) {
        double share = share(decision, tier);
        if (share <= 0 || chosen == null || arms == null) return Optional.empty();
        if (!governance.learningOn()) return Optional.empty();
        if (routingKey != null && parameters.inHoldout(routingKey)) return Optional.empty();
        List<String> others = arms.stream().filter(a -> a != null && !a.equals(chosen)).distinct().toList();
        if (others.isEmpty()) return Optional.empty();
        long seed = RANDOM.nextLong();
        return Optional.of(draw(chosen, others, share, seed));
    }

    /** The seeded draw: with probability {@code share} one of {@code others} uniformly, else {@code chosen}. */
    static DecisionLearningHooks.Exploration draw(String chosen, List<String> others, double share, long seed) {
        Random r = new Random(seed);
        if (r.nextDouble() < share) return new DecisionLearningHooks.Exploration(others.get(r.nextInt(others.size())), share / others.size(), share, seed);
        return new DecisionLearningHooks.Exploration(chosen, 1.0 - share, share, seed);
    }
}
