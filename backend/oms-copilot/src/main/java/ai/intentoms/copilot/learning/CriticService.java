package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.Review;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Critic (SOO requirements doc §6.1): reviews a proposal's changeset against its evaluation report. Its
 * verdict is evidence attached to the proposal for a human reviewer — it never approves, rejects or blocks on its
 * own, regardless of how risky its own verdict reads.
 */
@Service
public class CriticService {
    private static final Logger log = LoggerFactory.getLogger(CriticService.class);
    private static final String FEATURE = "learning_critic";

    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final ProposalRepository proposals;
    private final LearningLlmAudit audit;

    public CriticService(CopilotLlmClient llm, PromptTemplates templates, ProposalRepository proposals, LearningLlmAudit audit) {
        this.audit = audit;
        this.llm = llm;
        this.templates = templates;
        this.proposals = proposals;
    }

    /** Reviews an EVALUATED proposal (the evaluator must have written an evaluation report first). */
    public Optional<Review> review(String proposalId) {
        Proposal p = proposals.find(proposalId).orElseThrow(() -> new ValidationException("Proposal " + proposalId + " not found"));
        if (p.evaluationReport() == null || p.evaluationReport().isEmpty()) { log.info("Critic skipped {}: no evaluation report yet", proposalId); return Optional.empty(); }
        if (!llm.available()) { log.info("Critic skipped: no copilot model key configured"); return Optional.empty(); }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", p.changeset().target().toString());
        payload.put("tier", p.tier().name());
        payload.put("motivatedBy", p.changeset().motivatedBy());
        payload.put("changes", p.changeset().changes().stream().map(c -> Map.of("type", c.getClass().getSimpleName(), "detail", c.toString())).toList());
        payload.put("evaluationReport", p.evaluationReport());

        if (!audit.withinBudget("critic", FEATURE, "proposal", proposalId, LearningSchemas.REVIEW, "system-critic.md")) {
            log.info("Critic skipped {}: monthly learning LLM budget reached", proposalId);
            return Optional.empty();
        }
        String prompt = templates.get("system-critic.md");
        JsonNode out;
        try { out = llm.complete(FEATURE, prompt, payload, LearningSchemas.REVIEW); }
        catch (OmsException e) {
            audit.record("critic", FEATURE, "proposal", proposalId, LearningSchemas.REVIEW, "system-critic.md", prompt, "error", null);
            log.warn("Critic call failed for proposal {}: {}", proposalId, e.getMessage());
            return Optional.empty();
        }

        List<String> risks = new ArrayList<>();
        for (JsonNode r : out.path("risks")) risks.add(r.asText());
        Review review = new Review(Review.Verdict.valueOf(out.path("verdict").asText("NEEDS_CHANGES")), risks);
        proposals.setCriticReview(proposalId, review);
        audit.record("critic", FEATURE, "proposal", proposalId, LearningSchemas.REVIEW, "system-critic.md", prompt, "ok", proposalId);
        return Optional.of(review);
    }
}
