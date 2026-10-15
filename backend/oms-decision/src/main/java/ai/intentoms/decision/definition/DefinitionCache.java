package ai.intentoms.decision.definition;

import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Short-lived cache of the active (live/canary/shadow) versions per decision so the hot path never parses YAML per
 * call. Studio mutations invalidate; other instances converge within the TTL.
 */
@Component
public class DefinitionCache {
    private final Cache<String, List<DecisionDefinitionRepository.Row>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(2)).maximumSize(10_000).build();

    public List<DecisionDefinitionRepository.Row> active(String name, Function<String, List<DecisionDefinitionRepository.Row>> loader) {
        return cache.get(TenantContext.require() + "|" + name, k -> loader.apply(name));
    }

    public void invalidate(String name) { cache.invalidate(TenantContext.require() + "|" + name); }

    public void invalidateAll() { cache.invalidateAll(); }
}
