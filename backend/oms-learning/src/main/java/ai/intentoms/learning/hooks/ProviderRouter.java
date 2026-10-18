package ai.intentoms.learning.hooks;

import java.util.Optional;

/** DF-05: the provider to try first for a decision, when a promoted routing change names one. */
public interface ProviderRouter {
    Optional<String> preferredProvider(String decision, int version);
}
