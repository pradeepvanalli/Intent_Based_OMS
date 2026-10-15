package ai.intentoms.decision.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy-time settings for the L2 LLM providers ({@code oms.decision.llm.*}). {@code provider} selects which one the
 * alias {@code llm} / {@code llm.structured} resolves to (anthropic | openai). Base URLs are overridable for tests and
 * gateways. Prices are list prices per million tokens used for the cost column.
 */
@ConfigurationProperties(prefix = "oms.decision.llm")
public record LlmProperties(String provider, String anthropicApiKey, String anthropicModel, String anthropicBaseUrl, String openaiApiKey, String openaiBaseUrl, String openaiModel,
                            int timeoutMs, double anthropicInputUsdPerMillion, double anthropicOutputUsdPerMillion, double openaiInputUsdPerMillion, double openaiOutputUsdPerMillion) {
    public static final String OPENROUTER_KEY_PREFIX = "sk-or-";
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String OPENROUTER_DEFAULT_MODEL = "anthropic/claude-sonnet-4.5";

    public LlmProperties {
        boolean openRouter = openaiApiKey != null && openaiApiKey.startsWith(OPENROUTER_KEY_PREFIX);
        boolean hasAnthropic = anthropicApiKey != null && !anthropicApiKey.isBlank();
        boolean hasOpenAi = openaiApiKey != null && !openaiApiKey.isBlank();
        // "auto": prefer a direct Anthropic key, else any OpenAI-compatible key (incl. OpenRouter), else Anthropic (unavailable).
        if (provider == null || provider.isBlank() || provider.equalsIgnoreCase("auto")) provider = hasAnthropic ? "anthropic" : hasOpenAi ? "openai" : "anthropic";
        if (anthropicModel == null || anthropicModel.isBlank()) anthropicModel = "claude-sonnet-5";
        if (anthropicBaseUrl == null || anthropicBaseUrl.isBlank()) anthropicBaseUrl = "https://api.anthropic.com";
        if (openaiBaseUrl == null || openaiBaseUrl.isBlank()) openaiBaseUrl = openRouter ? OPENROUTER_BASE_URL : "https://api.openai.com/v1";
        if (openaiModel == null || openaiModel.isBlank()) openaiModel = openRouter ? OPENROUTER_DEFAULT_MODEL : "gpt-5";
        if (timeoutMs <= 0) timeoutMs = 8000;
        if (anthropicInputUsdPerMillion <= 0) anthropicInputUsdPerMillion = 2.0;
        if (anthropicOutputUsdPerMillion <= 0) anthropicOutputUsdPerMillion = 10.0;
        if (openaiInputUsdPerMillion <= 0) openaiInputUsdPerMillion = 1.25;
        if (openaiOutputUsdPerMillion <= 0) openaiOutputUsdPerMillion = 10.0;
    }
    public boolean hasAnthropicKey() { return anthropicApiKey != null && !anthropicApiKey.isBlank(); }
    public boolean hasOpenAiKey() { return openaiApiKey != null && !openaiApiKey.isBlank(); }
    /** Canonical provider name the {@code llm} alias resolves to. */
    public String defaultLlmProviderName() { return "openai".equalsIgnoreCase(provider) ? OpenAiCompatibleDecisionProvider.NAME : AnthropicDecisionProvider.NAME; }
}
