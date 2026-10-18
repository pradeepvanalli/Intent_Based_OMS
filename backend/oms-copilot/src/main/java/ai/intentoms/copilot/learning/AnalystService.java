package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Analyst (SOO requirements doc §6.1/§6.2): aggregates-only in, testable {@link Hypothesis} list out. Runs on
 * the slow loop (default nightly) across every decision with mature episodes — this is explicitly not a tight
 * sweep. Never acts; every hypothesis it raises is {@code OPEN} and waits for the Author.
 */
@Service
public class AnalystService {
    private static final Logger log = LoggerFactory.getLogger(AnalystService.class);
    private static final String FEATURE = "learning_analyst";

    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final OutcomeDigestBuilder digests;
    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;
    private final LearningEvents events;

    public AnalystService(CopilotLlmClient llm, PromptTemplates templates, OutcomeDigestBuilder digests, DecisionRecordRepository decisionRecords,
                           HypothesisRepository hypotheses, LearningEvents events) {
        this.llm = llm;
        this.templates = templates;
        this.digests = digests;
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
        this.events = events;
    }

    @Scheduled(cron = "${oms.learning.analyst-cron:0 30 2 * * *}")
    public void scheduled() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(90))));
        for (String tenant : tenants) {
            try { TenantContext.runAs(tenant, this::runOnce); }
            catch (RuntimeException e) { log.error("Analyst run failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    /** One pass over every decision with recent mature episodes. Returns the hypotheses raised, for tests/ops. */
    public List<Hypothesis> runOnce() {
        if (!llm.available()) { log.info("Analyst skipped: no copilot model key configured"); return List.of(); }
        Instant since = Instant.now().minus(Duration.ofDays(90));
        List<Hypothesis> raised = new ArrayList<>();
        for (String decision : decisionRecords.decisionsWithRecords(since)) {
            raised.addAll(analyze(decision, since));
        }
        return raised;
    }

    public List<Hypothesis> analyze(String decision, Instant since) {
        List<OutcomeDigest> cohorts = digests.build(decision, since);
        if (cohorts.isEmpty()) return List.of();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision);
        payload.put("cohorts", cohorts.stream().map(c -> Map.of(
            "scopeKey", c.scopeKey(), "scopeValue", c.scopeValue(), "episodeCount", c.episodeCount(), "meanReward", c.meanReward(), "meanComponents", c.meanComponents())).toList());
        JsonNode out;
        try { out = llm.complete(FEATURE, templates.get("system-analyst.md"), payload, LearningSchemas.HYPOTHESES); }
        catch (OmsException e) { log.warn("Analyst call failed for decision {}: {}", decision, e.getMessage()); return List.of(); }
        List<Hypothesis> result = new ArrayList<>();
        for (JsonNode h : out.path("hypotheses")) {
            Hypothesis hyp = new Hypothesis(Ids.prefixed("hyp"),
                Map.of("scopeKey", h.path("scopeKey").asText(), "scopeValue", h.path("scopeValue").asText()),
                h.path("targetMetric").asText(), Direction.valueOf(h.path("expectedDirection").asText()),
                Map.of("target", h.path("leverTarget").asText(), "description", h.path("leverDescription").asText()),
                clamp01(h.path("priorConfidence").asDouble(0.5)), List.of("decision:" + decision + " cohort:" + h.path("scopeKey").asText() + "=" + h.path("scopeValue").asText()),
                truncate(h.path("rationale").asText(""), 280), Hypothesis.Status.OPEN, Instant.now());
            hypotheses.insert(hyp);
            events.hypothesisRaised(hyp.hypothesisId(), Actor.system("analyst"), Map.of("decision", decision, "targetMetric", hyp.targetMetric(), "priorConfidence", hyp.priorConfidence()));
            result.add(hyp);
        }
        return result;
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
}
