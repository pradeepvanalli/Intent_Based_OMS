package ai.intentoms.decision.provider;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TypeSafe Jev System One provider (FR-DEC-08, PRD section 9). {@code POST {base-url}/v1/systemone} with
 * {@code Authorization: Bearer <key>}; request {@code {state, model, questions:{key:{type, instructions, criteria}}}};
 * response {@code {model, answers:{key:{type, choice|score|noul, probabilities, confidence, legend}}, usage}}.
 * Budget = min(request timeout, configured timeout) (GR-07); one retry with 50-150 ms jitter on 429/529 when the budget
 * still allows. Noul confidence is {@code |2p-1|} (section 9 rule 7). Cost = input tokens x $0.042/1e6 (output free).
 */
@Component
public class JevDecisionProvider implements DecisionProvider {
    public static final String NAME = "jev";
    private static final Logger log = LoggerFactory.getLogger(JevDecisionProvider.class);
    private final JevProperties props;
    private final RestClient client;
    private final Metrics metrics;

    public JevDecisionProvider(JevProperties props, RestClient.Builder builder, Metrics metrics) {
        this.props = props;
        this.metrics = metrics;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(HttpSupport.factoryTimeout(props.timeoutMs()));
        this.client = builder.clone().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    @Override public String name() { return NAME; }

    @Override public boolean available() { return props.enabled() && props.hasKey(); }

    @Override
    public ProviderResponse ask(ProviderRequest req) {
        if (!available()) throw ProviderException.of(NAME, "provider not configured", null);
        int budget = Math.min(req.timeoutMs(), props.timeoutMs()) + props.latencyAllowanceMs();   // GR-07 budget plus the configured gateway allowance
        long start = System.nanoTime();
        String body = Json.write(toRequest(req));
        HttpSupport.HttpResult res;
        try {
            res = HttpSupport.withBudget(NAME, budget, () -> post(body));
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if ((res.status() == 429 || res.status() == 529) && elapsed + HttpSupport.retryJitterMs() + 100 < budget) {
                long jitter = HttpSupport.retryJitterMs();
                log.info("Jev returned {}, retrying once after {} ms", res.status(), jitter);
                sleep(jitter);
                int remaining = (int) (budget - (System.nanoTime() - start) / 1_000_000);
                res = HttpSupport.withBudget(NAME, remaining, () -> post(body));
            }
        } finally {
            metrics.record("oms.decision.provider.latency", Duration.ofNanos(System.nanoTime() - start), "provider", NAME);
        }
        if (res.status() != 200) throw ProviderException.status(NAME, res.status(), res.body());
        long latency = (System.nanoTime() - start) / 1_000_000;
        return parse(req, res.body(), latency);
    }

    private HttpSupport.HttpResult post(String body) {
        return client.post().uri("/v1/systemone")
            .header("Authorization", "Bearer " + props.apiKey())
            .header("HTTP-Referer", "https://github.com/intentoms/intent-oms")   // OpenRouter attribution (ignored by TypeSafe)
            .header("X-Title", "Intent OMS")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange((request, response) -> new HttpSupport.HttpResult(response.getStatusCode().value(), new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    /** Request body exactly as the Jev API expects. */
    public Map<String, Object> toRequest(ProviderRequest req) {
        Map<String, Object> questions = new LinkedHashMap<>();
        for (QuestionDef q : req.questions().values()) {
            Map<String, Object> jq = new LinkedHashMap<>();
            jq.put("type", q.type());
            jq.put("instructions", q.effectiveInstructions());
            if (q.isChoice()) jq.put("criteria", q.choiceCriteria());
            else if (q.isScore()) jq.put("criteria", q.scoreLevels());
            else if (q.isNoul() && !q.choiceCriteria().isEmpty()) jq.put("criteria", q.choiceCriteria());
            questions.put(q.key(), jq);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", req.state());
        body.put("model", req.model() != null && !req.model().isBlank() ? req.model() : props.model());
        body.put("questions", questions);
        return body;
    }

    /** Parses the Jev response into typed answers. */
    @SuppressWarnings("unchecked")
    public ProviderResponse parse(ProviderRequest req, String body, long latencyMs) {
        Map<String, Object> raw;
        try { raw = Json.readMap(body); } catch (IllegalArgumentException e) { throw ProviderException.of(NAME, "unparseable response", e); }
        Map<String, Object> answers = raw.get("answers") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Answer> out = new LinkedHashMap<>();
        for (QuestionDef q : req.questions().values()) {
            Object a = answers.get(q.key());
            if (!(a instanceof Map<?, ?> am)) throw ProviderException.of(NAME, "missing answer for question " + q.key(), null);
            Map<String, Object> ans = (Map<String, Object>) am;
            Map<String, Double> probs = new LinkedHashMap<>();
            if (ans.get("probabilities") instanceof Map<?, ?> pm) pm.forEach((k, v) -> probs.put(String.valueOf(k), num(v)));
            double confidence = num(ans.get("confidence"));
            if (q.isChoice()) {
                String choice = String.valueOf(ans.get("choice"));
                if (!q.choiceCriteria().containsKey(choice)) throw ProviderException.of(NAME, "choice '" + choice + "' is not an option of " + q.key(), null); // GR-04
                out.put(q.key(), Answer.choice(choice, probs, confidence));
            } else if (q.isScore()) {
                Map<String, String> legend = new LinkedHashMap<>();
                if (ans.get("legend") instanceof Map<?, ?> lm) lm.forEach((k, v) -> legend.put(String.valueOf(k), String.valueOf(v)));
                out.put(q.key(), Answer.score(num(ans.get("score")), probs, confidence, legend));
            } else {
                out.put(q.key(), Answer.noul(num(ans.get("noul"))));
            }
        }
        int inTok = 0, outTok = 0;
        Double gatewayCost = null;
        if (raw.get("usage") instanceof Map<?, ?> u) {
            inTok = (int) num(u.get("input_tokens")); outTok = (int) num(u.get("output_tokens"));
            if (u.get("cost") instanceof Number c) gatewayCost = c.doubleValue();   // OpenRouter reports the billed cost
        }
        double cost = gatewayCost != null ? gatewayCost : inTok * props.inputUsdPerMillionTokens() / 1_000_000d;
        return new ProviderResponse(out, String.valueOf(raw.getOrDefault("model", props.model())), inTok, outTok, cost, latencyMs, raw);
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : o == null ? 0 : Double.parseDouble(String.valueOf(o)); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

}
