package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Author (SOO requirements doc §6.1-§6.3): turns one open {@link Hypothesis} into a {@link PolicyChangeset},
 * staged as a {@code DRAFT} {@link Proposal}. Reuses {@link CopilotLlmClient}'s schema-constrained-decoding
 * pipeline exactly like the existing policy copilot — this is the "Author (extends policy copilot)" extension
 * point named in the requirements doc §5.1. Never publishes; a drafted proposal still needs evaluation, a critic
 * review and a human approver who isn't its author before it can promote.
 */
@Service
public class AuthorService {
    private static final Logger log = LoggerFactory.getLogger(AuthorService.class);
    private static final String FEATURE = "learning_author";
    /** AllowListChange must never let refund/cancel into an autonomous allow-list — same hard rule as the Control Tower. */
    private static final List<String> NEVER_AUTONOMOUS = List.of("refund", "cancel");

    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final DecisionStudioService decisions;
    private final HypothesisRepository hypotheses;
    private final ProposalRepository proposals;
    private final LearningEvents events;

    public AuthorService(CopilotLlmClient llm, PromptTemplates templates, DecisionStudioService decisions, HypothesisRepository hypotheses, ProposalRepository proposals, LearningEvents events) {
        this.llm = llm;
        this.templates = templates;
        this.decisions = decisions;
        this.hypotheses = hypotheses;
        this.proposals = proposals;
        this.events = events;
    }

    /** Drafts a changeset from one open hypothesis, if the LLM is available and its output validates. */
    public Optional<Proposal> draft(String hypothesisId) {
        Hypothesis h = hypotheses.find(hypothesisId).orElseThrow(() -> new ValidationException("Hypothesis " + hypothesisId + " not found"));
        if (h.status() != Hypothesis.Status.OPEN) return Optional.empty();
        if (!llm.available()) { log.info("Author skipped: no copilot model key configured"); return Optional.empty(); }
        String leverTarget = String.valueOf(h.lever().get("target"));
        ArtifactRef ref;
        try { ref = ArtifactRef.parse(leverTarget); } catch (IllegalArgumentException e) { log.warn("Hypothesis {} has an unparseable lever target {}", hypothesisId, leverTarget); return Optional.empty(); }

        Map<String, Object> target = currentState(ref);
        if (target == null) return Optional.empty();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hypothesis", Map.of("targetMetric", h.targetMetric(), "expectedDirection", h.expectedDirection().name(),
            "leverTarget", leverTarget, "leverDescription", String.valueOf(h.lever().get("description")), "rationale", h.rationale(), "priorConfidence", h.priorConfidence()));
        payload.put("currentState", target);

        JsonNode out;
        try { out = llm.complete(FEATURE, templates.get("system-author.md"), payload, LearningSchemas.CHANGESET); }
        catch (OmsException e) { log.warn("Author call failed for hypothesis {}: {}", hypothesisId, e.getMessage()); return Optional.empty(); }

        List<PolicyChange> changes = parseChanges(out.path("changes"), target);
        if (changes.isEmpty()) return Optional.empty();
        PolicyChangeset.RiskTier tier = PolicyChangeset.RiskTier.valueOf(out.path("tier").asText("T1"));
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef(out.path("targetType").asText(ref.type()), out.path("targetName").asText(ref.name())),
            (Integer) target.getOrDefault("version", 0), changes, out.path("motivatedBy").asText(h.rationale()), tier);

        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, hypothesisId, tier, ProposalStatus.DRAFT, null, null, "system:author", Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null);
        proposals.insert(p);
        hypotheses.updateStatus(hypothesisId, Hypothesis.Status.DRAFTED);
        events.proposalDrafted(p.proposalId(), Actor.system("author"), Map.of("hypothesisId", hypothesisId, "target", changeset.target().toString(), "changeCount", changes.size()));
        return Optional.of(p);
    }

    private Map<String, Object> currentState(ArtifactRef ref) {
        if (!"decision".equals(ref.type())) return null; // parameters: Wave-0 FS/IU learners own parameter versions; not this fork's scope
        try {
            List<DecisionDefinitionRepository.Row> versions = decisions.versions(ref.name());
            DecisionDefinitionRepository.Row live = versions.stream().filter(r -> "live".equals(r.definition().status())).findFirst()
                .orElseGet(() -> versions.get(versions.size() - 1));
            DecisionDefinition d = live.definition();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", d.version());
            m.put("tier", d.tier().name());
            m.put("actGte", d.effectiveBands().actGte());
            m.put("confirmGte", d.effectiveBands().confirmGte());
            return m;
        } catch (RuntimeException e) {
            log.warn("Could not read current state for decision {}: {}", ref.name(), e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<PolicyChange> parseChanges(JsonNode arr, Map<String, Object> target) {
        List<PolicyChange> out = new ArrayList<>();
        for (JsonNode c : arr) {
            String type = c.path("type").asText();
            String argsJson = c.path("argsJson").asText("{}");
            Map<String, Object> args;
            try { args = Json.readMap(argsJson); } catch (IllegalArgumentException e) { log.warn("Author produced invalid argsJson for {}: {}", type, e.getMessage()); continue; }
            args = new LinkedHashMap<>(args);
            args.put("type", type);
            if ("AllowListChange".equals(type) && Boolean.TRUE.equals(args.get("add")) && NEVER_AUTONOMOUS.contains(String.valueOf(args.get("entry")).toLowerCase(java.util.Locale.ROOT))) {
                log.warn("Author attempted to add {} to an allow-list; refused (never-autonomous rule)", args.get("entry"));
                continue;
            }
            PolicyChange change;
            try { change = Json.convert(args, PolicyChange.class); }
            catch (IllegalArgumentException e) { log.warn("Author produced an unparseable {}: {}", type, e.getMessage()); continue; }
            if (!referencesLiveState(change, target)) {
                log.warn("Author proposed a {} that doesn't match the live artifact's actual state; refused (hallucinated-lever guard, NF safety §11)", type);
                continue;
            }
            out.add(change);
        }
        return out;
    }

    /**
     * Cross-field reference check (requirements doc §6.3, §11 "LLM hallucinated lever"): a {@link
     * PolicyChange.ThresholdChange} must name a band this artifact actually has, and its {@code from} must match
     * the artifact's real current value — the model isn't trusted to have quoted the live state correctly, only to
     * have proposed where it should move. Static and side-effect-free so it's independently unit-testable without a
     * live LLM call or a Spring context.
     */
    static boolean referencesLiveState(PolicyChange change, Map<String, Object> target) {
        if (!(change instanceof PolicyChange.ThresholdChange tc) || target == null) return true;
        Object live = switch (tc.band() == null ? "" : tc.band().toLowerCase(java.util.Locale.ROOT)) {
            case "act" -> target.get("actGte");
            case "confirm" -> target.get("confirmGte");
            default -> null;
        };
        if (!(live instanceof Number liveValue)) return false;
        return Math.abs(liveValue.doubleValue() - tc.from()) < 1e-6;
    }
}
