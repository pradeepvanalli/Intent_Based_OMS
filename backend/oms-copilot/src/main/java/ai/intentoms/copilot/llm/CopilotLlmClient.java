package ai.intentoms.copilot.llm;

import ai.intentoms.copilot.config.CopilotProperties;
import ai.intentoms.platform.error.GuardrailViolationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Structured-output client for the OpenAI-compatible chat completions API (OpenRouter by default). Every call sends a
 * strict JSON schema ({@code response_format: json_schema, strict: true}; GR-04), validates the answer against it,
 * retries on 429/5xx/transport errors, refuses when the tenant's daily AI budget is spent (GR-17) and writes a
 * {@code copilot_usage} row with tokens, cost and latency. Log lines carry feature, model, status, tokens and latency so
 * they correlate with OpenTelemetry traces; latency and call counts are also exported as Micrometer metrics.
 */
@Component
public class CopilotLlmClient {
    private static final Logger log = LoggerFactory.getLogger(CopilotLlmClient.class);
    private final CopilotProperties props;
    private final RestClient client;
    private final UsageLedger ledger;
    private final BudgetPolicy budget;
    private final Metrics metrics;

    public CopilotLlmClient(CopilotProperties props, RestClient.Builder builder, UsageLedger ledger, BudgetPolicy budget, Metrics metrics) {
        this.props = props;
        this.ledger = ledger;
        this.budget = budget;
        this.metrics = metrics;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(Math.max(1000, props.timeoutMs())));
        this.client = builder.clone().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    public boolean available() { return props.hasKey(); }
    public String model() { return props.model(); }

    /**
     * One structured completion. {@code userPayload} is serialised to JSON and sent as the user message; untrusted text
     * inside it must already be framed with {@link Untrusted#text}. Returns the parsed, schema-validated JSON object.
     */
    public JsonNode complete(String feature, String systemPrompt, Object userPayload, JsonSchema schema) {
        if (!available()) throw new LlmException("No copilot API key configured (oms.copilot.api-key)");
        enforceBudget(feature);
        String body = Json.write(request(systemPrompt, userPayload, schema));
        int attempts = props.maxRetries() + 1;
        long start = System.nanoTime();
        HttpResult res = null;
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                res = post(body);
                if (res.status() == 200) break;
                if (!retryable(res.status()) || attempt == attempts) break;
                log.warn("copilot.llm retry feature={} model={} status={} attempt={}", feature, props.model(), res.status(), attempt);
            } catch (ResourceAccessException e) {
                last = e;
                if (attempt == attempts) break;
                log.warn("copilot.llm retry feature={} model={} error={} attempt={}", feature, props.model(), e.getMessage(), attempt);
            }
            sleep(backoffMs(attempt));
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        if (res == null) {
            record(feature, props.model(), 0, 0, 0, latencyMs, "error");
            throw new LlmException("Model endpoint unreachable: " + (last == null ? "unknown error" : last.getMessage()), Map.of("feature", feature), last);
        }
        if (res.status() != 200) {
            record(feature, props.model(), 0, 0, 0, latencyMs, "error");
            throw new LlmException("Model endpoint returned HTTP " + res.status(), Map.of("feature", feature, "status", res.status(), "body", truncate(res.body(), 600)), null);
        }
        return parse(feature, schema, res.body(), latencyMs);
    }

    Map<String, Object> request(String systemPrompt, Object userPayload, JsonSchema schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPayload instanceof String s ? s : Json.write(userPayload))));
        Map<String, Object> js = new LinkedHashMap<>();
        js.put("name", schema.name());
        js.put("strict", true);
        js.put("schema", schema.schema());
        body.put("response_format", Map.of("type", "json_schema", "json_schema", js));
        body.put("max_tokens", props.maxOutputTokens());
        body.put("temperature", 0.1);
        return body;
    }

    private HttpResult post(String body) {
        return client.post().uri("/chat/completions")
            .header("Authorization", "Bearer " + props.apiKey())
            .header("HTTP-Referer", "https://github.com/intentoms/intent-oms")
            .header("X-Title", "Intent OMS Copilot")
            .contentType(MediaType.APPLICATION_JSON).body(body)
            .exchange((rq, rs) -> new HttpResult(rs.getStatusCode().value(), new String(rs.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    @SuppressWarnings("unchecked")
    private JsonNode parse(String feature, JsonSchema schema, String body, long latencyMs) {
        Map<String, Object> raw = Json.readMap(body);
        String model = String.valueOf(raw.getOrDefault("model", props.model()));
        int inTok = 0, outTok = 0;
        Double gatewayCost = null;
        if (raw.get("usage") instanceof Map<?, ?> u) {
            inTok = intOf(u.get("prompt_tokens"));
            outTok = intOf(u.get("completion_tokens"));
            if (u.get("cost") instanceof Number c) gatewayCost = c.doubleValue();
        }
        double cost = gatewayCost != null ? gatewayCost : inTok * props.inputUsdPerMillion() / 1e6 + outTok * props.outputUsdPerMillion() / 1e6;
        String content = null;
        if (raw.get("choices") instanceof List<?> choices && !choices.isEmpty() && choices.get(0) instanceof Map<?, ?> c0 && c0.get("message") instanceof Map<?, ?> msg) {
            if (msg.get("content") instanceof String s) content = s;
            if (msg.get("refusal") instanceof String r && !r.isBlank()) {
                record(feature, model, inTok, outTok, cost, latencyMs, "refused");
                throw new LlmException("Model refused the request: " + truncate(r, 300), Map.of("feature", feature), null);
            }
        }
        if (content == null || content.isBlank()) {
            record(feature, model, inTok, outTok, cost, latencyMs, "error");
            throw new LlmException("Model response contained no structured content", Map.of("feature", feature), null);
        }
        JsonNode node;
        try { node = Json.tree(stripFences(content)); }
        catch (IllegalArgumentException e) {
            record(feature, model, inTok, outTok, cost, latencyMs, "error");
            throw new LlmException("Model output is not valid JSON", Map.of("feature", feature, "content", truncate(content, 600)), e);
        }
        List<String> problems = schema.validate(node);
        if (!problems.isEmpty()) {
            record(feature, model, inTok, outTok, cost, latencyMs, "error");
            throw new LlmException("Model output does not match schema " + schema.name() + ": " + String.join("; ", problems), Map.of("feature", feature, "problems", problems), null);
        }
        record(feature, model, inTok, outTok, cost, latencyMs, "ok");
        return node;
    }

    private void enforceBudget(String feature) {
        OptionalDouble remaining = budget.remainingUsd();
        if (remaining.isPresent() && remaining.getAsDouble() <= 0) {
            metrics.count("oms.copilot.llm.budget_refusals", "feature", feature);
            throw new GuardrailViolationException("Daily AI budget exhausted for this tenant (GR-17); the copilot refuses new model calls until tomorrow",
                Map.of("feature", feature, "budgetUsd", budget.dailyBudgetUsd().orElse(0), "usedTodayUsd", budget.usedTodayUsd()));
        }
    }

    private void record(String feature, String model, int inTok, int outTok, double cost, long latencyMs, String status) {
        try { ledger.record(new LlmUsage(feature, model, inTok, outTok, cost, latencyMs, status)); }
        catch (RuntimeException e) { log.warn("copilot.llm usage not recorded feature={} error={}", feature, e.getMessage()); }
        metrics.record("oms.copilot.llm.latency", Duration.ofMillis(latencyMs), "feature", feature, "model", model);
        metrics.count("oms.copilot.llm.calls", "feature", feature, "status", status);
        log.info("copilot.llm feature={} model={} status={} inputTokens={} outputTokens={} costUsd={} latencyMs={}", feature, model, status, inTok, outTok, String.format(java.util.Locale.ROOT, "%.6f", cost), latencyMs);
    }

    static boolean retryable(int status) { return status == 429 || status >= 500; }

    static long backoffMs(int attempt) { return (long) (300 * Math.pow(2, attempt - 1)) + ThreadLocalRandom.current().nextLong(100); }

    static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static int intOf(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static String truncate(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    record HttpResult(int status, String body) {}
}
