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
 * L2 provider for any OpenAI-compatible chat completions endpoint (GPT, Gemini via gateway, open-weight servers;
 * FR-DEC-08). Uses {@code response_format: json_schema, strict: true} so the answer is typed (GR-04); the state is sent
 * as data in the user message (GR-08).
 */
@Component
public class OpenAiCompatibleDecisionProvider implements DecisionProvider {
    public static final String NAME = "llm.openai";
    private final LlmProperties props;
    private final RestClient client;
    private final Metrics metrics;

    public OpenAiCompatibleDecisionProvider(LlmProperties props, RestClient.Builder builder, Metrics metrics) {
        this.props = props;
        this.metrics = metrics;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(HttpSupport.factoryTimeout(props.timeoutMs()));
        this.client = builder.clone().baseUrl(props.openaiBaseUrl()).requestFactory(factory).build();
    }

    @Override public String name() { return NAME; }
    @Override public boolean available() { return props.hasOpenAiKey(); }

    @Override
    public ProviderResponse ask(ProviderRequest req) {
        if (!available()) throw ProviderException.of(NAME, "provider not configured", null);
        int budget = Math.min(req.timeoutMs(), props.timeoutMs());
        long start = System.nanoTime();
        String body = Json.write(toRequest(req));
        HttpSupport.HttpResult res;
        try {
            res = HttpSupport.withBudget(NAME, budget, () -> client.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + props.openaiApiKey())
            .header("HTTP-Referer", "https://github.com/pradeepvanalli/Intent_Based_OMS")
            .header("X-Title", "Intent OMS").contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((rq, rs) -> new HttpSupport.HttpResult(rs.getStatusCode().value(), new String(rs.getBody().readAllBytes(), StandardCharsets.UTF_8)), false));
        } finally {
            metrics.record("oms.decision.provider.latency", Duration.ofNanos(System.nanoTime() - start), "provider", NAME);
        }
        if (res.status() != 200) throw ProviderException.status(NAME, res.status(), res.body());
        return parse(req, res.body(), (System.nanoTime() - start) / 1_000_000);
    }

    public Map<String, Object> toRequest(ProviderRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model() != null && !req.model().isBlank() ? req.model() : props.openaiModel());
        body.put("messages", List.of(Map.of("role", "system", "content", LlmSchema.SYSTEM_PROMPT.replace("calling the `answer` tool exactly once", "returning one JSON object matching the schema")),
            Map.of("role", "user", "content", LlmSchema.userMessage(req))));
        body.put("response_format", Map.of("type", "json_schema", "json_schema", Map.of("name", "answer", "strict", true, "schema", LlmSchema.schema(req.questions()))));
        return body;
    }

    @SuppressWarnings("unchecked")
    public ProviderResponse parse(ProviderRequest req, String body, long latencyMs) {
        Map<String, Object> raw = Json.readMap(body);
        String content = null;
        if (raw.get("choices") instanceof List<?> choices && !choices.isEmpty() && choices.get(0) instanceof Map<?, ?> c0 && c0.get("message") instanceof Map<?, ?> msg) {
            Object ct = msg.get("content");
            if (ct instanceof String s) content = s;
        }
        if (content == null || content.isBlank()) throw ProviderException.of(NAME, "response contained no structured content", null);
        Map<String, Object> parsed;
        try { parsed = Json.readMap(content); } catch (IllegalArgumentException e) { throw ProviderException.of(NAME, "structured content is not JSON", e); }
        Map<String, Answer> answers = LlmSchema.toAnswers(NAME, req.questions(), parsed);
        int inTok = 0, outTok = 0;
        Double gatewayCost = null;
        if (raw.get("usage") instanceof Map<?, ?> u) {
            inTok = (int) LlmSchema.num(u.get("prompt_tokens"), 0); outTok = (int) LlmSchema.num(u.get("completion_tokens"), 0);
            if (u.get("cost") instanceof Number c) gatewayCost = c.doubleValue();   // OpenRouter reports the billed cost
        }
        double cost = gatewayCost != null ? gatewayCost : inTok * props.openaiInputUsdPerMillion() / 1e6 + outTok * props.openaiOutputUsdPerMillion() / 1e6;
        return new ProviderResponse(answers, String.valueOf(raw.getOrDefault("model", props.openaiModel())), inTok, outTok, cost, latencyMs, raw);
    }
}
