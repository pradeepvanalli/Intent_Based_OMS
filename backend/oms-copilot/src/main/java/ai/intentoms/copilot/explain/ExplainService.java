package ai.intentoms.copilot.explain;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.Grounding;
import ai.intentoms.copilot.llm.JsonSchema;
import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.CustomerSafeTraceView;
import ai.intentoms.decision.model.DecisionTrace;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explain copilot (FR-XAI-03, FR-XAI-04): a plain-language "why" written from {@link DecisionService#why}. The customer
 * audience only ever sees the customer-safe projection (no margin, risk, cost, probabilities). The text may only state
 * facts in the trace: every number in the output is checked against the trace and, on any miss or model failure, a
 * deterministic template takes over. The result is labelled {@code generated} and links to the raw trace.
 */
@Service
public class ExplainService {
    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);
    private static final JsonSchema SCHEMA = JsonSchema.named("explanation", JsonSchema.object("text", JsonSchema.string("The explanation")));
    private final DecisionService decisions;
    private final CopilotLlmClient llm;
    private final PromptTemplates templates;

    public ExplainService(DecisionService decisions, CopilotLlmClient llm, PromptTemplates templates) {
        this.decisions = decisions;
        this.llm = llm;
        this.templates = templates;
    }

    public Explanation explain(String recordId, String audience) {
        String aud = audience == null || audience.isBlank() ? "internal" : audience.toLowerCase(Locale.ROOT);
        if (!aud.equals("internal") && !aud.equals("customer")) throw new ValidationException("audience must be internal or customer");
        DecisionTrace trace = decisions.why(recordId);
        Object source = aud.equals("customer") ? CustomerSafeTraceView.from(trace) : trace;
        String traceUrl = "/api/v1/decision-records/" + recordId + "/why?audience=" + aud;
        if (llm.available()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("audience", aud);
                payload.put("trace", source);
                String text = llm.complete("explain", templates.get("system-explain.md"), payload, SCHEMA).path("text").asText();
                List<String> ungrounded = Grounding.ungroundedNumbers(text, source);
                if (ungrounded.isEmpty() && !text.isBlank()) return new Explanation(text, aud, true, recordId, llm.model(), traceUrl, null);
                log.warn("explain for {} rejected: numbers not in trace {}", recordId, ungrounded);
                return new Explanation(deterministic(trace, aud), aud, false, recordId, null, traceUrl, "generated text contained numbers absent from the trace: " + ungrounded);
            } catch (OmsException e) {
                log.warn("explain for {} fell back: {}", recordId, e.getMessage());
                return new Explanation(deterministic(trace, aud), aud, false, recordId, null, traceUrl, e.getMessage());
            }
        }
        return new Explanation(deterministic(trace, aud), aud, false, recordId, null, traceUrl, "no model key configured");
    }

    /** Template built only from trace fields; used when no model is available or its output failed the grounding check. */
    static String deterministic(DecisionTrace t, String audience) {
        Object value = t.override() != null && t.override().get("value") != null ? t.override().get("value") : t.value();
        if (audience.equals("customer")) {
            StringBuilder sb = new StringBuilder();
            sb.append("Your order was handled by our ").append(t.decision().replace('_', ' ')).append(" check, which recorded the outcome \"").append(value).append("\".");
            if (t.requiresHuman()) sb.append(" A member of our team is reviewing it before anything else happens.");
            else sb.append(" This outcome was applied automatically following our standard policy.");
            if (t.reasonCodes() != null && !t.reasonCodes().isEmpty()) sb.append(" Reason codes: ").append(String.join(", ", t.reasonCodes())).append('.');
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Decision ").append(t.decision()).append(" v").append(t.version()).append(" (tier ").append(t.tier()).append(") returned ").append(Json.write(value))
            .append(" with action '").append(t.action()).append("' in band ").append(t.band()).append(" via layer ").append(t.layer()).append('.');
        if (t.rulesFired() != null && !t.rulesFired().isEmpty()) sb.append(" L0 rules fired: ").append(String.join(", ", t.rulesFired())).append('.');
        if (t.answers() != null && !t.answers().isEmpty()) {
            sb.append(" Answers:");
            for (var e : t.answers().entrySet()) { Answer a = e.getValue(); sb.append(' ').append(e.getKey()).append('=').append(Json.write(a.value())).append(" (confidence ").append(String.format(Locale.ROOT, "%.2f", a.confidence())).append(");"); }
        }
        sb.append(" Overall confidence ").append(String.format(Locale.ROOT, "%.2f", t.confidence())).append(" against act threshold ").append(t.bands() == null ? "n/a" : String.valueOf(t.bands().actGte())).append('.');
        if (t.provider() != null) sb.append(" Provider ").append(t.provider()).append(t.latencyMs() > 0 ? " in " + t.latencyMs() + " ms" : "").append('.');
        if (t.requiresHuman()) sb.append(" The case was escalated to a person.");
        if (t.override() != null) sb.append(" A human override changed the value: ").append(t.override().get("reason")).append('.');
        return sb.toString();
    }
}
