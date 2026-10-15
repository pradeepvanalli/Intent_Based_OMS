package com.example.oms.extensions;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.provider.DecisionProvider;
import ai.intentoms.decision.provider.ProviderException;
import ai.intentoms.decision.provider.ProviderRequest;
import ai.intentoms.decision.provider.ProviderResponse;
import ai.intentoms.platform.json.Json;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Example L1 provider backed by your own classification service (FR-DEC-08, GR-04).
 *
 * <p>Contract: answer every question in {@link ProviderRequest#questions()} in one call within
 * {@link ProviderRequest#timeoutMs()}, and return <em>only</em> enumerated values:
 * <ul>
 *   <li>{@code choice}: one key of {@link QuestionDef#choiceCriteria()} plus a probability per option;</li>
 *   <li>{@code score}: a level index {@code 0..n-1} of {@link QuestionDef#scoreLevels()};</li>
 *   <li>{@code noul}: a probability that {@link QuestionDef#statement()} is true.</li>
 * </ul>
 * The runtime validates answers against the definition anyway, discards anything outside the options and applies
 * the default (GR-04), so an invalid answer costs you a decision, not an incident. Throw {@link ProviderException}
 * for transport failures; mark 429/503 as retryable so the runtime retries once inside the budget before falling
 * through to {@code fallbackProvider}.
 *
 * <p>Register it as a {@code @Component}; refer to it from YAML with {@code provider: my-classifier} (the value of
 * {@link #name()}). {@code GET /api/v1/decisions/providers} shows whether {@link #available()} is true. Kill switch:
 * tenant key {@code provider.kill_switch.my-classifier}.
 */
@Component
public class CustomDecisionProvider implements DecisionProvider {
    public static final String NAME = "my-classifier";
    private final RestClient http;
    private final String url;
    private final String apiKey;

    public CustomDecisionProvider(RestClient.Builder builder, org.springframework.core.env.Environment env) {
        this.url = env.getProperty("example.classifier.url", "");
        this.apiKey = env.getProperty("example.classifier.api-key", "");
        this.http = builder.build();
    }

    @Override public String name() { return NAME; }

    /** False when not configured, so the runtime skips this provider in the chain instead of failing. */
    @Override public boolean available() { return !url.isBlank() && !apiKey.isBlank(); }

    @Override
    public ProviderResponse ask(ProviderRequest req) {
        long started = System.nanoTime();
        // 1. Build a request your service understands. Keep the state as data; instructions are the questions.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", req.state());
        Map<String, Object> questions = new LinkedHashMap<>();
        req.questions().forEach((key, q) -> {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", q.type());
            switch (q.type()) {
                case "choice" -> { spec.put("instructions", q.instructions()); spec.put("options", q.choiceCriteria()); }
                case "score" -> { spec.put("instructions", q.instructions()); spec.put("levels", q.scoreLevels()); }
                case "noul" -> spec.put("statement", q.statement());
                default -> throw ProviderException.of(NAME, "unknown question type " + q.type(), null);
            }
            questions.put(key, spec);
        });
        body.put("questions", questions);

        // 2. Call it within the budget.
        Map<?, ?> raw;
        try {
            raw = http.post().uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-Timeout-Ms", String.valueOf(req.timeoutMs()))
                .body(Json.write(body))
                .retrieve().body(Map.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw ProviderException.status(NAME, e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw ProviderException.timeout(NAME, req.timeoutMs());
        }
        if (raw == null) throw ProviderException.of(NAME, "empty response", null);

        // 3. Map onto typed answers. Only enumerated values survive.
        Map<String, Answer> answers = new LinkedHashMap<>();
        Map<?, ?> out = (Map<?, ?>) raw.getOrDefault("answers", Map.of());
        req.questions().forEach((key, q) -> {
            Map<?, ?> a = (Map<?, ?>) out.get(key);
            if (a == null) return;                                  // missing answer → runtime treats as no answer
            switch (q.type()) {
                case "choice" -> {
                    @SuppressWarnings("unchecked") Map<String, Double> probs = (Map<String, Double>) a.get("probabilities");
                    String option = String.valueOf(a.get("value"));
                    if (!q.choiceCriteria().containsKey(option)) return;   // not an option: drop it (GR-04)
                    answers.put(key, Answer.choice(option, probs, probs == null ? 0.0 : probs.getOrDefault(option, 0.0)));
                }
                case "score" -> {
                    int level = ((Number) a.get("value")).intValue();
                    if (level < 0 || level >= q.scoreLevels().size()) return;
                    @SuppressWarnings("unchecked") Map<String, Double> probs = (Map<String, Double>) a.get("probabilities");
                    double conf = a.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
                    answers.put(key, Answer.score(level, probs, conf, legend(q.scoreLevels())));
                }
                case "noul" -> answers.put(key, Answer.noul(((Number) a.get("value")).doubleValue()));
                default -> { }
            }
        });
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        int inTok = raw.get("input_tokens") instanceof Number n ? n.intValue() : 0;
        int outTok = raw.get("output_tokens") instanceof Number n ? n.intValue() : 0;
        double cost = inTok * 0.10 / 1_000_000;                       // your price; recorded per decision (GR-17)
        return new ProviderResponse(answers, String.valueOf(raw.getOrDefault("model", "my-classifier-v1")), inTok, outTok, cost, latencyMs,
            Map.of("provider", NAME));                                 // raw payload kept for the trace; no PII
    }

    private static Map<String, String> legend(List<String> levels) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < levels.size(); i++) m.put(String.valueOf(i), levels.get(i));
        return m;
    }
}
