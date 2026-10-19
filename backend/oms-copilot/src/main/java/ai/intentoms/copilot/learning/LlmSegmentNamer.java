package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.JsonSchema;
import ai.intentoms.learning.learner.SegmentNamer;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CI-02 analyst naming: asks the copilot model for a short, neutral name and one-sentence description per discovered
 * segment, from aggregate statistics only (sizes and feature means, never customer identifiers). Empty when no copilot
 * key is configured or the call fails, so discovery falls back to the template names.
 */
@Component
public class LlmSegmentNamer implements SegmentNamer {
    private static final Logger log = LoggerFactory.getLogger(LlmSegmentNamer.class);
    /** Metered as a {@code learning_*} feature so it counts against the monthly learning LLM budget (NF-03). */
    static final String FEATURE = "learning_segment_naming";
    static final String SYSTEM = """
        You name customer segments for an order-management team. Each segment comes with its size and the average of four
        behaviours: split_tolerance (0..1, how happily they accept an order in several packages), speed_vs_cost (-1 cost
        conscious .. +1 speed first), pickup_share (0..1 of orders collected in store) and orders (orders per customer).
        Compare each segment with the overall averages. Give every segment a label of at most five words in plain English
        that a store manager would understand, and one sentence saying what sets it apart and what it means for fulfilment.
        Describe behaviour only; never refer to demographics, protected characteristics or individuals. Return exactly one
        name per segment, in the order given.""";

    static final JsonSchema SCHEMA = JsonSchema.named("segment_names", JsonSchema.object(
        "segments", JsonSchema.array("One entry per segment, same order as the input", JsonSchema.object(
            "label", JsonSchema.string("Five words or fewer"),
            "description", JsonSchema.string("One sentence, max 200 characters")))));

    static final String ROLE = "analyst";
    static final String PROMPT = "segment-naming";

    private final CopilotLlmClient llm;
    private final LearningLlmAudit audit;

    public LlmSegmentNamer(CopilotLlmClient llm, LearningLlmAudit audit) {
        this.llm = llm;
        this.audit = audit;
    }

    /**
     * NF-03 / §6.3: the call counts against the tenant's monthly learning LLM budget (its feature is metered with the
     * other {@code learning_*} features) and is written to the learning LLM audit with its schema and prompt hashes;
     * when the budget is spent the call is skipped and the template names are used.
     */
    @Override
    public Optional<List<Name>> name(List<Profile> profiles, Map<String, Double> overallMeans) {
        if (!llm.available() || profiles.isEmpty()) return Optional.empty();
        if (!audit.withinBudget(ROLE, FEATURE, "segments", null, SCHEMA, PROMPT)) return Optional.empty();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("overall", overallMeans);
        List<Map<String, Object>> segs = new ArrayList<>();
        for (Profile p : profiles) segs.add(Map.of("size", p.size(), "means", p.means()));
        payload.put("segments", segs);
        try {
            JsonNode out = llm.complete(FEATURE, SYSTEM, payload, SCHEMA);
            List<Name> names = new ArrayList<>();
            for (JsonNode n : out.path("segments")) {
                String label = n.path("label").asText("").trim();
                String desc = n.path("description").asText("").trim();
                if (label.isEmpty()) { audit.record(ROLE, FEATURE, "segments", null, SCHEMA, PROMPT, SYSTEM, "invalid", null); return Optional.empty(); }
                names.add(new Name(label.length() > 60 ? label.substring(0, 60) : label, desc.length() > 240 ? desc.substring(0, 240) : desc));
            }
            boolean ok = names.size() == profiles.size();
            audit.record(ROLE, FEATURE, "segments", null, SCHEMA, PROMPT, SYSTEM, ok ? "ok" : "invalid", null);
            return ok ? Optional.of(names) : Optional.empty();
        } catch (RuntimeException e) {
            audit.record(ROLE, FEATURE, "segments", null, SCHEMA, PROMPT, SYSTEM, "error", null);
            log.warn("Segment naming by the analyst model failed, using template names: {}", e.toString());
            return Optional.empty();
        }
    }
}
