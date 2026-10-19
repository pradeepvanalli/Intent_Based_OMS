package ai.intentoms.learning.hooks;

import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.learner.ProviderRoutingLearner;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DF-05 on the live path: when a provider-routing proposal for a decision has been promoted, its target
 * {@code config:learning.provider_route.<decision>} holds the provider to try first, and this router returns it. The
 * decision's own provider and fallback stay in the chain behind it, so an unavailable routed provider degrades to the
 * original. Routing stops (back to the decision's configured provider) while the provider-routing learner is switched
 * off, paused or at L0 (GV-03), and is undone by rolling the promotion back.
 */
@Component
public class PromotedProviderRouter implements ProviderRouter {
    public static final String KEY_PREFIX = "learning.provider_route.";

    private final TenantConfigStore config;
    private final LearnerGovernance governance;
    private final ai.intentoms.learning.governance.ParameterResolver parameters;

    public PromotedProviderRouter(TenantConfigStore config, LearnerGovernance governance, ai.intentoms.learning.governance.ParameterResolver parameters) {
        this.config = config;
        this.governance = governance;
        this.parameters = parameters;
    }

    @Override
    public Optional<String> preferredProvider(String decision, int version) {
        String routed = config.getString(KEY_PREFIX + decision, null);
        if (routed == null || routed.isBlank()) return Optional.empty();
        if (!governance.gate(ProviderRoutingLearner.KEY).mayFeedLive()) return Optional.empty();
        // EX-04: a holdout customer stays on the decision's configured provider.
        if (parameters.inHoldout(ai.intentoms.learning.governance.LearningSubject.current().orElse(null))) return Optional.empty();
        return Optional.of(routed);
    }
}
