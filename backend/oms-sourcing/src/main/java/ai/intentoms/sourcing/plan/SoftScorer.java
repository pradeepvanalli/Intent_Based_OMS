package ai.intentoms.sourcing.plan;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.Transit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code sourcing_node_score} call per sourcing run (FR-SRC-02, FR-SRC-04, FR-SRC-05; section 9 rules 1, 3, 5): the
 * decision module scores every candidate (two-stage top-K above 255) and this adapter reads
 * {@code details.topK.ranking[]{id, score, confidence}} (fallback {@code answers.ranking.probabilities}) into a
 * node → soft score map in [0,1]. Nodes outside the top K get the lowest ranked score. When the decision comes back on
 * {@code Layer.DEFAULT} (AI down), is in the escalate band, or throws, every node gets the neutral 0.5 (NFR-05) — the
 * run never fails because of the model.
 */
@Service
public class SoftScorer {
    public static final String DECISION = "sourcing_node_score";
    private static final Logger log = LoggerFactory.getLogger(SoftScorer.class);

    /** Soft scores plus the trace the plan explain carries. */
    public record SoftScores(Map<String, Double> byNode, String decisionRecordId, String layer, String band, String source, List<String> notes) {
        public static SoftScores neutral(String note) { return new SoftScores(Map.of(), null, Layer.DEFAULT.name(), null, "neutral", List.of(note)); }
        public Map<String, Object> explain() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", DECISION);
            m.put("decisionRecordId", decisionRecordId == null ? "" : decisionRecordId);
            m.put("layer", layer);
            m.put("band", band == null ? "" : band);
            m.put("source", source);
            m.put("scores", byNode);
            if (!notes.isEmpty()) m.put("notes", notes);
            return m;
        }
    }

    private final DecisionService decisions;
    private final TimeSource time;
    private final Metrics metrics;
    private final LearnedFactRepository learnedFacts;

    public SoftScorer(DecisionService decisions, TimeSource time, Metrics metrics, LearnedFactRepository learnedFacts) {
        this.decisions = decisions;
        this.time = time;
        this.metrics = metrics;
        this.learnedFacts = learnedFacts;
    }

    public SoftScores score(SourcingRequest req, List<Candidate> candidates, RateCard card, Actor actor) {
        if (candidates.isEmpty()) return SoftScores.neutral("no candidates");
        Map<String, Object> input = input(req, candidates, card, time.now());
        overlayLearnedReliability(input, candidates);
        DecisionResult r;
        try {
            r = decisions.decide(DECISION, input, DecisionOptions.live("order", req.orderId(), actor));
        } catch (RuntimeException e) {
            log.warn("sourcing_node_score unavailable for order {}: {}", req.orderId(), e.getMessage());
            metrics.count("oms.sourcing.soft_score.unavailable");
            return SoftScores.neutral("decision unavailable: " + e.getMessage());
        }
        return fromResult(r);
    }

    /** Builds the minimal typed state the decision declares (section 9 rule 2). */
    public static Map<String, Object> input(SourcingRequest req, List<Candidate> candidates, RateCard card, Instant now) {
        double weight = 0;
        for (var l : req.lines()) weight += l.weightKg() * l.qty();
        List<Map<String, Object>> cands = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.nodeId());
            m.put("type", c.node().type());
            m.put("backlog_units", c.node().backlogUnits());
            m.put("reject_rate", c.node().rejectRate());
            m.put("staffing", c.node().staffing());
            m.put("minutes_to_cutoff", Transit.minutesToCutoff(c.node(), now));
            m.put("distance_km", c.distanceKm());
            m.put("cost_usd", req.isPickup() ? 0.0 : card.quote(req.shipMethod(), c.distanceKm(), weight).cost().amount().doubleValue());
            cands.add(m);
        }
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("destination_state", req.destination().state() == null ? "" : req.destination().state());
        order.put("ship_method", req.shipMethod());
        order.put("line_count", req.lines().size());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("order", order);
        input.put("candidates", cands);
        return input;
    }

    @SuppressWarnings("unchecked")
    static SoftScores fromResult(DecisionResult r) {
        List<String> notes = new ArrayList<>();
        if (r.layer() == Layer.DEFAULT) {
            notes.add("decision returned L0 default (" + r.details().getOrDefault("defaultReason", "unknown") + "); neutral soft scores (NFR-05)");
            return new SoftScores(Map.of(), r.recordId(), r.layer().name(), r.band() == null ? null : r.band().name(), "default", notes);
        }
        if (r.band() == Band.ESCALATE) {
            notes.add("decision in escalate band (confidence " + r.confidence() + "); soft scores not trusted, neutral used (GR-02)");
            return new SoftScores(Map.of(), r.recordId(), r.layer().name(), r.band().name(), "escalate", notes);
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        String source = "topK";
        Object topK = r.details().get("topK");
        if (topK instanceof Map<?, ?> tk && tk.get("ranking") instanceof List<?> ranking) {
            for (Object row : ranking) {
                if (!(row instanceof Map<?, ?> m) || m.get("id") == null) continue;
                double s = m.get("score") instanceof Number n ? n.doubleValue() : 0.5;
                scores.put(String.valueOf(m.get("id")), clamp(s));
            }
        }
        if (scores.isEmpty()) {
            Answer a = r.answers().get("ranking");
            if (a != null && !a.probabilities().isEmpty()) {
                source = "answers.ranking";
                double max = a.probabilities().values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
                a.probabilities().forEach((id, p) -> scores.put(id, max <= 0 ? 0.5 : clamp(p / max)));
            }
        }
        if (scores.isEmpty()) {
            notes.add("decision carried no ranking; neutral soft scores");
            return new SoftScores(Map.of(), r.recordId(), r.layer().name(), r.band().name(), "none", notes);
        }
        return new SoftScores(scores, r.recordId(), r.layer().name(), r.band().name(), source, notes);
    }

    /** FS-02: overlays each candidate's static {@code reject_rate} with the learned Beta-binomial posterior and adds
     * {@code on_time_rate}, once a node has enough observed deliveries to trust (sample_size &gt;= 10) — below that
     * the static node config value is kept untouched, so a brand-new tenant with no history behaves exactly as
     * before this learner existed. */
    @SuppressWarnings("unchecked")
    private void overlayLearnedReliability(Map<String, Object> input, List<Candidate> candidates) {
        if (!(input.get("candidates") instanceof List<?> cands)) return;
        for (Object o : cands) {
            if (!(o instanceof Map<?, ?> m) || m.get("id") == null) continue;
            Map<String, Object> cand = (Map<String, Object>) m;
            String nodeId = String.valueOf(cand.get("id"));
            try {
                learnedFacts.active(SubjectType.NODE, nodeId, FactKey.NODE_REJECT_RATE)
                    .filter(f -> f.sampleSize() >= 10 && f.value() instanceof TypedValue.NumberValue)
                    .ifPresent(f -> cand.put("reject_rate", ((TypedValue.NumberValue) f.value()).value()));
                learnedFacts.active(SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE)
                    .filter(f -> f.sampleSize() >= 10 && f.value() instanceof TypedValue.NumberValue)
                    .ifPresent(f -> cand.put("on_time_rate", ((TypedValue.NumberValue) f.value()).value()));
            } catch (RuntimeException e) {
                log.warn("Learned reliability lookup failed for node {}: {}", nodeId, e.getMessage());
            }
        }
    }

    /** Lowest ranked score for candidates outside the returned ranking (they were scored lower than every ranked node). */
    public static double floorScore(Map<String, Double> scores) {
        return scores.isEmpty() ? PlanScorer.NEUTRAL_SOFT : scores.values().stream().mapToDouble(Double::doubleValue).min().orElse(PlanScorer.NEUTRAL_SOFT);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
