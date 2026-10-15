package ai.intentoms.decision.provider;

import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves providers by name with availability and tenant kill switches (FR-DEC-08, GR-06). Aliases {@code llm} and
 * {@code llm.structured} map to the configured default LLM provider. A provider is usable when it is configured and
 * {@code provider.kill_switch.<name>} is not true for the tenant.
 */
@Component
public class ProviderRegistry {
    private final Map<String, DecisionProvider> providers = new LinkedHashMap<>();
    private final TenantConfigStore config;
    private final LlmProperties llm;

    public ProviderRegistry(List<DecisionProvider> all, TenantConfigStore config, LlmProperties llm) {
        all.forEach(p -> providers.put(p.name(), p));
        this.config = config;
        this.llm = llm;
    }

    public String canonicalName(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        if (n.equals("llm") || n.equals("llm.structured") || n.equals("llm.default")) return llm.defaultLlmProviderName();
        if (n.equals("anthropic") || n.equals("claude")) return AnthropicDecisionProvider.NAME;
        if (n.equals("openai") || n.equals("gpt")) return OpenAiCompatibleDecisionProvider.NAME;
        return n;
    }

    /** The provider if it exists, is configured and is not killed for the tenant. */
    public Optional<DecisionProvider> usable(String name) {
        DecisionProvider p = providers.get(canonicalName(name));
        if (p == null || !p.available() || killed(p.name())) return Optional.empty();
        return Optional.of(p);
    }

    public Optional<DecisionProvider> get(String name) { return Optional.ofNullable(providers.get(canonicalName(name))); }

    public boolean killed(String providerName) {
        return config.getBoolean(ConfigKeys.PROVIDER_KILL_SWITCH_PREFIX + canonicalName(providerName), false);
    }

    /** The default L2 provider, when configured and not killed. */
    public Optional<DecisionProvider> llm() { return usable(llm.defaultLlmProviderName()); }

    /** Availability snapshot for the studio/admin UI. */
    public Map<String, Boolean> availability() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        providers.forEach((n, p) -> out.put(n, p.available() && !killed(n)));
        return out;
    }
}
