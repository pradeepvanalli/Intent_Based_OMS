package ai.intentoms.decision.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy-time settings for the TypeSafe Jev System One provider ({@code oms.decision.jev.*}). The provider is available
 * only when enabled and an API key is present; otherwise definitions fall through to their fallback provider (section 9
 * rule 9).
 */
@ConfigurationProperties(prefix = "oms.decision.jev")
public record JevProperties(boolean enabled, String apiKey, String baseUrl, String model, int timeoutMs, double inputUsdPerMillionTokens, Integer latencyAllowanceMs) {
    /** OpenRouter keys start with this prefix; they route to OpenRouter's System One endpoint with the typesafe/ namespace. */
    public static final String OPENROUTER_KEY_PREFIX = "sk-or-";
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api";
    public static final String OPENROUTER_MODEL = "typesafe/jev-1.13";
    public static final String TYPESAFE_BASE_URL = "https://api.typesafe.ai";
    public static final String TYPESAFE_MODEL = "jev-latest";

    public JevProperties {
        boolean openRouter = apiKey != null && apiKey.startsWith(OPENROUTER_KEY_PREFIX);
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = openRouter ? OPENROUTER_BASE_URL : TYPESAFE_BASE_URL;
        if (model == null || model.isBlank()) model = openRouter ? OPENROUTER_MODEL : TYPESAFE_MODEL;
        if (timeoutMs <= 0) timeoutMs = 800;
        if (inputUsdPerMillionTokens <= 0) inputUsdPerMillionTokens = 0.042;
        // A gateway hop (OpenRouter) adds network latency the PRD's 800 ms budget did not anticipate; allow for it unless configured.
        if (latencyAllowanceMs == null || latencyAllowanceMs < 0) latencyAllowanceMs = (openRouter || (baseUrl != null && baseUrl.contains("openrouter.ai"))) ? 1200 : 0;
    }
    public boolean hasKey() { return apiKey != null && !apiKey.isBlank(); }
    /** True when requests go through OpenRouter (key prefix or base URL). */
    public boolean viaOpenRouter() { return (apiKey != null && apiKey.startsWith(OPENROUTER_KEY_PREFIX)) || (baseUrl != null && baseUrl.contains("openrouter.ai")); }
}
