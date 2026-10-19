package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.promotion.ChangesetApplier;
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
 * The Author (SOO requirements doc §6.1-§6.3): turns one open {@link Hypothesis} into a {@link PolicyChangeset}, staged as
 * a {@code DRAFT} {@link Proposal}. Reuses {@link CopilotLlmClient}'s schema-constrained decoding like the policy copilot.
 * Never publishes; a draft still needs evaluation, a critic review and an approver who is not its author.
 *
 * <p>Hypotheses come from the Analyst (a {@code lever.target}) and from the override-to-rule and rule learners
 * ({@code lever.decision} plus an action), so the author can write the L0 rule people keep applying by hand (DF-04). The
 * model sees the live decision's bands and rules and, for an override pattern, the tokenised state of one of the decisions
 * people overrode, never the raw input. Every change it returns is re-validated against the live artifact (§6.3, §11
 * "hallucinated lever"): a threshold must quote the live value, a retired rule must exist, and the whole changeset applied
 * to the live YAML must lint clean, so an invalid CEL expression never becomes a proposal.
 *
 * <p>Without a model key, or once the monthly learning budget is spent, the {@link TemplateAuthor} writes what a
 * hypothesis states precisely (a rule people agreed on, a rule to retire, an abuse rule, an allow-list change), labelled
 * as template output. The model is always preferred when it is available.
 */
@Service
public class AuthorService {
    private static final Logger log = LoggerFactory.getLogger(AuthorService.class);
    private static final String FEATURE = "learning_author";
    private static final String PROMPT = "system-author.md";
    /** AllowListChange must never let refund/cancel into an autonomous allow-list — same hard rule as the Control Tower. */
    private static final List<String> NEVER_AUTONOMOUS = List.of("refund", "cancel");

    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final DecisionStudioService decisions;
    private final DecisionDefinitionRepository definitions;
    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;
    private final ProposalRepository proposals;
    private final LearningEvents events;
    private final LearningLlmAudit audit;
    private final TemplateAuthor template;

    public AuthorService(CopilotLlmClient llm, PromptTemplates templates, DecisionStudioService decisions, DecisionDefinitionRepository definitions,
                         DecisionRecordRepository decisionRecords, HypothesisRepository hypotheses, ProposalRepository proposals, LearningEvents events, LearningLlmAudit audit,
                         TemplateAuthor template) {
        this.template = template;
        this.llm = llm;
        this.templates = templates;
        this.decisions = decisions;
        this.definitions = definitions;
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
        this.proposals = proposals;
        this.events = events;
        this.audit = audit;
    }

    /** The artifact a hypothesis points at: an explicit {@code lever.target}, else the decision a learner named. */
    static String leverTarget(Hypothesis h) {
        Object t = h.lever().get("target");
        if (t != null && !String.valueOf(t).isBlank() && !"null".equals(String.valueOf(t))) return String.valueOf(t);
        Object d = h.lever().get("decision");
        return d == null ? null : "decision:" + d;
    }

    /** Drafts a changeset from one open hypothesis, if the LLM is available and its output validates. */
    public Optional<Proposal> draft(String hypothesisId) {
        Hypothesis h = hypotheses.find(hypothesisId).orElseThrow(() -> new ValidationException("Hypothesis " + hypothesisId + " not found"));
        if (h.status() != Hypothesis.Status.OPEN) return Optional.empty();
        if (TemplateAuthor.allowList(h)) return template.draft(h); // a config list change: exact, no model needed
        if (!llm.available()) { log.info("Author: no copilot model key configured, using the template author"); return template.draft(h); }
        String leverTarget = leverTarget(h);
        ArtifactRef ref;
        try { ref = ArtifactRef.parse(leverTarget == null ? "" : leverTarget); }
        catch (IllegalArgumentException e) { log.warn("Hypothesis {} has no usable lever target ({})", hypothesisId, leverTarget); return Optional.empty(); }

        DecisionDefinition live = liveDecision(ref);
        if (live == null) return Optional.empty();
        Map<String, Object> target = currentState(live);
        if (!audit.withinBudget("author", FEATURE, "hypothesis", hypothesisId, LearningSchemas.CHANGESET, PROMPT)) {
            log.info("Author: monthly learning LLM budget reached for {}, using the template author", hypothesisId);
            return template.draft(h);
        }

        Map<String, Object> hyp = new LinkedHashMap<>();
        hyp.put("targetMetric", h.targetMetric());
        hyp.put("expectedDirection", h.expectedDirection().name());
        hyp.put("leverTarget", leverTarget);
        hyp.put("leverDescription", String.valueOf(h.lever().getOrDefault("description", h.lever().getOrDefault("action", ""))));
        hyp.put("rationale", h.rationale());
        hyp.put("priorConfidence", h.priorConfidence());
        if (h.lever().get("suggestedThen") != null) hyp.put("suggestedThen", h.lever().get("suggestedThen"));
        if (h.lever().get("ruleId") != null) hyp.put("ruleId", h.lever().get("ruleId"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hypothesis", hyp);
        payload.put("currentState", target);
        exampleState(h).ifPresent(s -> payload.put("exampleTokenisedState", s));

        String prompt = templates.get(PROMPT);
        JsonNode out;
        try { out = llm.complete(FEATURE, prompt, payload, LearningSchemas.CHANGESET); }
        catch (OmsException e) {
            audit.record("author", FEATURE, "hypothesis", hypothesisId, LearningSchemas.CHANGESET, PROMPT, prompt, "error", null);
            log.warn("Author call failed for hypothesis {}: {}", hypothesisId, e.getMessage());
            return Optional.empty();
        }

        List<PolicyChange> changes = parseChanges(out.path("changes"), target);
        if (!changes.isEmpty() && !lintsClean(live, changes)) changes = List.of();
        if (changes.isEmpty()) {
            audit.record("author", FEATURE, "hypothesis", hypothesisId, LearningSchemas.CHANGESET, PROMPT, prompt, "invalid", null);
            return Optional.empty();
        }
        PolicyChangeset.RiskTier tier = PolicyChangeset.RiskTier.valueOf(live.tier().name()); // the decision's own tier, never the model's claim
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", live.name()), live.version(), changes, out.path("motivatedBy").asText(h.rationale()), tier);

        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, hypothesisId, tier, ProposalStatus.DRAFT, null, null, "system:author", Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null);
        proposals.insert(p);
        hypotheses.updateStatus(hypothesisId, Hypothesis.Status.DRAFTED);
        audit.record("author", FEATURE, "hypothesis", hypothesisId, LearningSchemas.CHANGESET, PROMPT, prompt, "ok", p.proposalId());
        // proposal.drafted is recorded by ProposalRepository.insert (GV-04); the schema hash is on the audit row above.
        return Optional.of(p);
    }

    private DecisionDefinition liveDecision(ArtifactRef ref) {
        if (!"decision".equals(ref.type())) return null; // parameter and config levers are drafted by their fast-loop learners
        try {
            return definitions.byStatus(ref.name(), "live").stream().findFirst().map(DecisionDefinitionRepository.Row::definition).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read decision {}: {}", ref.name(), e.toString());
            return null;
        }
    }

    static Map<String, Object> currentState(DecisionDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision", d.name());
        m.put("version", d.version());
        m.put("tier", d.tier().name());
        m.put("actGte", d.effectiveBands().actGte());
        m.put("confirmGte", d.effectiveBands().confirmGte());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RuleDef r : d.rules()) rules.add(Map.of("id", r.id(), "when", r.when(), "then", String.valueOf(r.then())));
        m.put("rules", rules);
        m.put("ruleIds", d.rules().stream().map(RuleDef::id).toList());
        m.put("stateFields", d.stateInclude());
        d.main().filter(q -> q.isChoice()).ifPresent(q -> m.put("options", new ArrayList<>(q.choiceCriteria().keySet())));
        return m;
    }

    /** The tokenised state (GR-09) of one of the decisions behind an override pattern, so a rule can name real fields. */
    private Optional<Map<String, Object>> exampleState(Hypothesis h) {
        for (String id : h.evidence() == null ? List.<String>of() : h.evidence()) {
            var r = decisionRecords.find(id);
            if (r.isPresent() && r.get().state() != null && !r.get().state().isEmpty()) return Optional.of(r.get().state());
        }
        return Optional.empty();
    }

    private boolean lintsClean(DecisionDefinition live, List<PolicyChange> changes) {
        try {
            var lint = decisions.lint(ChangesetApplier.applyToDecision(live.yaml(), changes));
            if (!lint.ok()) log.warn("Author changeset for {} does not lint, refused: {}", live.name(), lint.issues());
            return lint.ok();
        } catch (RuntimeException e) {
            log.warn("Author changeset for {} cannot be applied to the live definition, refused: {}", live.name(), e.getMessage());
            return false;
        }
    }

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
     * Cross-field reference check (requirements doc §6.3, §11 "LLM hallucinated lever"): a {@link PolicyChange.ThresholdChange}
     * must name a band this artifact has and quote its real current value, a {@link PolicyChange.RuleRetire} must name a rule
     * that exists, and any change naming a decision must name this one. Static and side-effect-free for unit tests.
     */
    @SuppressWarnings("unchecked")
    static boolean referencesLiveState(PolicyChange change, Map<String, Object> target) {
        if (target == null) return true;
        Object decision = target.get("decision");
        String named = switch (change) {
            case PolicyChange.ThresholdChange tc -> tc.decision();
            case PolicyChange.RuleAdd ra -> ra.decision();
            case PolicyChange.RuleRetire rr -> rr.decision();
            default -> null;
        };
        if (decision != null && named != null && !decision.equals(named)) return false;
        if (change instanceof PolicyChange.RuleRetire rr) {
            return target.get("ruleIds") instanceof List<?> ids && ids.contains(rr.ruleId());
        }
        if (!(change instanceof PolicyChange.ThresholdChange tc)) return true;
        Object live = switch (tc.band() == null ? "" : tc.band().toLowerCase(java.util.Locale.ROOT)) {
            case "act" -> target.get("actGte");
            case "confirm" -> target.get("confirmGte");
            default -> null;
        };
        if (!(live instanceof Number liveValue)) return false;
        return Math.abs(liveValue.doubleValue() - tc.from()) < 1e-6;
    }
}
