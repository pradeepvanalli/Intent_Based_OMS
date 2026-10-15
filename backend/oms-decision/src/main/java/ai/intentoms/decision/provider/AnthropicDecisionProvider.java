package ai.intentoms.decision.provider;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 provider on the Claude Messages API (FR-DEC-02, FR-DEC-08): {@code POST {base}/v1/messages} with headers
 * {@code x-api-key} and {@code anthropic-version: 2023-06-01}. A single strict tool {@code answer} whose input schema
 * enumerates every option forces typed output (GR-04); the system prompt forbids prose and untrusted text travels only
 * inside the user message framed as data (GR-08). Probabilities are one-hot; confidence comes from the model's required
 * per-question {@code confidence} field.
 */
@Component
public class AnthropicDecisionProvider implements DecisionProvider {
    public static final String NAME = "llm.anthropic";
    public static final String API_VERSION = "2023-06-01";
    private final LlmProperties props;
    private final RestClient client;
    private final Metrics metrics;

    public AnthropicDecisionProvider(LlmProperties props, RestClient.Builder builder, Metrics metrics) {
        this.props = props;
        this.metrics = metrics;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(HttpSupport.factoryTimeout(props.timeoutMs()));
        this.client = builder.clone().baseUrl(props.anthropicBaseUrl()).requestFactory(factory).build();
    }

    @Override public String name() { return NAME; }
    @Override public boolean available() { return props.hasAnthropicKey(); }

    @Override
    public ProviderResponse ask(ProviderRequest req) {
        if (!available()) throw ProviderException.of(NAME, "provider not configured", null);
        int budget = Math.min(req.timeoutMs(), props.timeoutMs());
        long start = System.nanoTime();
        String body = Json.write(toRequest(req));
        HttpSupport.HttpResult res;
        try {
            res = HttpSupport.withBudget(NAME, budget, () -> client.post().uri("/v1/messages")
                .header("x-api-key", props.anthropicApiKey()).header("anthropic-version", API_VERSION).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((rq, rs) -> new HttpSupport.HttpResult(rs.getStatusCode().value(), new String(rs.getBody().readAllBytes(), StandardCharsets.UTF_8)), false));
        } finally {
            metrics.record("oms.decision.provider.latency", Duration.ofNanos(System.nanoTime() - start), "provider", NAME);
        }
        if (res.status() != 200) throw ProviderException.status(NAME, res.status(), res.body());
        return parse(req, res.body(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Messages API request with the forced {@code answer} tool. */
    public Map<String, Object> toRequest(ProviderRequest req) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "answer");
        tool.put("description", "Record the typed answer to every question. Values must be one of the enumerated options.");
        tool.put("input_schema", LlmSchema.schema(req.questions()));
        tool.put("strict", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model() != null && !req.model().isBlank() ? req.model() : props.anthropicModel());
        body.put("max_tokens", 1024);
        body.put("system", LlmSchema.SYSTEM_PROMPT);
        body.put("tools", List.of(tool));
        body.put("tool_choice", Map.of("type", "tool", "name", "answer"));
        body.put("messages", List.of(Map.of("role", "user", "content", LlmSchema.userMessage(req))));
        return body;
    }

    @SuppressWarnings("unchecked")
    public ProviderResponse parse(ProviderRequest req, String body, long latencyMs) {
        Map<String, Object> raw = Json.readMap(body);
        Map<String, Object> input = null;
        if (raw.get("content") instanceof List<?> blocks) {
            for (Object b : blocks) if (b instanceof Map<?, ?> bm && "tool_use".equals(bm.get("type")) && "answer".equals(bm.get("name")) && bm.get("input") instanceof Map<?, ?> im) input = (Map<String, Object>) im;
        }
        if (input == null) throw ProviderException.of(NAME, "response contained no `answer` tool call (stop_reason=" + raw.get("stop_reason") + ")", null);
        Map<String, Answer> answers = LlmSchema.toAnswers(NAME, req.questions(), input);
        int inTok = 0, outTok = 0;
        if (raw.get("usage") instanceof Map<?, ?> u) { inTok = (int) LlmSchema.num(u.get("input_tokens"), 0); outTok = (int) LlmSchema.num(u.get("output_tokens"), 0); }
        double cost = inTok * props.anthropicInputUsdPerMillion() / 1e6 + outTok * props.anthropicOutputUsdPerMillion() / 1e6;
        return new ProviderResponse(answers, String.valueOf(raw.getOrDefault("model", props.anthropicModel())), inTok, outTok, cost, latencyMs, raw);
    }
}
