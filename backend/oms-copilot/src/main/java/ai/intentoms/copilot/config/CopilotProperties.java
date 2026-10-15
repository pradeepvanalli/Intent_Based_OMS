package ai.intentoms.copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy-time settings of the copilot ({@code oms.copilot.*}). The API key defaults to {@code OPENROUTER_API_KEY} then
 * {@code OPENAI_API_KEY}; an OpenRouter key ({@code sk-or-...}) routes to {@code https://openrouter.ai/api/v1} with the
 * model {@code anthropic/claude-sonnet-4.5} unless overridden. Without a key every copilot runs its deterministic
 * fallback and {@code /copilot/status} reports {@code available: false}.
 *
 * @param enabled                master switch; false forces every copilot onto its deterministic fallback (default true)
 * @param apiKey                 bearer token for the OpenAI-compatible chat completions endpoint
 * @param baseUrl                endpoint base; auto-detected from the key prefix when blank
 * @param model                  chat model id
 * @param timeoutMs              per-call read timeout (default 180 000 ms; proposals are long generations)
 * @param maxRetries             retries on 429 / 5xx / transport errors (default 1)
 * @param maxOutputTokens        {@code max_tokens} of a completion (default 16 000; change sets are large)
 * @param inputUsdPerMillion     list price used when the gateway does not report cost
 * @param outputUsdPerMillion    list price used when the gateway does not report cost
 * @param templatesDir           directory holding the prompt templates (default {@code <config-dir>/copilot})
 * @param starterDir             tenant directory whose decisions / state models seed a bootstrap (default demo tenant)
 */
@ConfigurationProperties(prefix = "oms.copilot")
public record CopilotProperties(Boolean enabled, String apiKey, String baseUrl, String model, int timeoutMs, Integer maxRetries, int maxOutputTokens,
                                double inputUsdPerMillion, double outputUsdPerMillion, String templatesDir, String starterDir) {
    public static final String OPENROUTER_KEY_PREFIX = "sk-or-";
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String OPENROUTER_DEFAULT_MODEL = "anthropic/claude-sonnet-4.5";
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String OPENAI_DEFAULT_MODEL = "gpt-5";

    public CopilotProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        apiKey = apiKey == null ? "" : apiKey.trim();
        boolean openRouter = apiKey.startsWith(OPENROUTER_KEY_PREFIX);
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = openRouter || apiKey.isEmpty() ? OPENROUTER_BASE_URL : OPENAI_BASE_URL;
        if (model == null || model.isBlank()) model = openRouter || apiKey.isEmpty() ? OPENROUTER_DEFAULT_MODEL : OPENAI_DEFAULT_MODEL;
        if (timeoutMs <= 0) timeoutMs = 180_000;   // a full ChangeSet proposal streams ~5k output tokens over the gateway: 60–90 s is normal
        if (maxRetries == null || maxRetries < 0) maxRetries = 1;
        if (maxOutputTokens <= 0) maxOutputTokens = 16_000;
        if (inputUsdPerMillion <= 0) inputUsdPerMillion = 3.0;
        if (outputUsdPerMillion <= 0) outputUsdPerMillion = 15.0;
        if (templatesDir == null || templatesDir.isBlank()) templatesDir = "../../config/copilot";
        if (starterDir == null || starterDir.isBlank()) starterDir = "../../config/tenants/demo";
    }

    public boolean hasKey() { return enabled && !apiKey.isEmpty(); }
}
