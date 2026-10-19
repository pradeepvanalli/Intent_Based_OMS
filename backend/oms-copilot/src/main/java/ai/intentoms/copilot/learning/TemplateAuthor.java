package ai.intentoms.copilot.learning;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.decision.studio.PromptVersionService;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.promotion.ChangesetApplier;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The deterministic Author used when no copilot model key is configured (or the monthly learning budget is spent), so
 * a tenant without an LLM still gets reviewable proposals from what the learners found (FD-04, DF-04, DF-06). It
 * writes only what a hypothesis already states precisely, as template output ({@code createdBy}
 * {@value #ACTOR}, motivation prefixed "Template author"):
 * <ul>
 *   <li>an override pattern ({@code add_rule} with a {@code when} condition and {@code suggestedThen}) becomes a
 *       {@code RuleAdd}; and, for the decision's main question, a draft prompt version that states the agreed guidance
 *       (DF-06; it goes through the prompt gate like any other prompt);</li>
 *   <li>a rule to retire ({@code retire_rule} with a {@code ruleId}) becomes a {@code RuleRetire};</li>
 *   <li>an address-reuse ring ({@code lever: ruleAdd} with an {@code addressToken}) becomes a {@code RuleAdd} that holds
 *       payments to that address; a channel with concentrated fraud ({@code lever: ruleAdd}, scope {@code channel}) one
 *       that sends that channel's orders to a person;</li>
 *   <li>an allow-list change ({@code lever: allowListChange} with {@code listName}, {@code entry}, {@code add}) becomes an
 *       {@code AllowListChange}, never adding refund or cancel to an autonomous list.</li>
 * </ul>
 * Every changeset is checked like the LLM author's: it must name the live artifact's real state and lint clean once
 * applied. Anything else is left for the LLM author or a person.
 */
@Component
public class TemplateAuthor {
    private static final Logger log = LoggerFactory.getLogger(TemplateAuthor.class);
    public static final String ACTOR = "system:template-author";
    private static final List<String> NEVER_AUTONOMOUS = List.of("refund", "cancel");

    private final DecisionStudioService studio;
    private final DecisionDefinitionRepository definitions;
    private final HypothesisRepository hypotheses;
    private final ProposalRepository proposals;
    private final PromptVersionService prompts;

    public TemplateAuthor(DecisionStudioService studio, DecisionDefinitionRepository definitions, HypothesisRepository hypotheses, ProposalRepository proposals,
                          PromptVersionService prompts) {
        this.studio = studio;
        this.definitions = definitions;
        this.hypotheses = hypotheses;
        this.proposals = proposals;
        this.prompts = prompts;
    }

    public Optional<Proposal> draft(Hypothesis h) {
        if (h.status() != Hypothesis.Status.OPEN) return Optional.empty();
        if (allowList(h)) return draftAllowList(h);
        String target = AuthorService.leverTarget(h);
        if (target == null) return Optional.empty();
        ArtifactRef ref;
        try { ref = ArtifactRef.parse(target); } catch (IllegalArgumentException e) { return Optional.empty(); }
        if (!"decision".equals(ref.type())) return Optional.empty();
        DecisionDefinition live = definitions.byStatus(ref.name(), "live").stream().findFirst().map(DecisionDefinitionRepository.Row::definition).orElse(null);
        if (live == null) return Optional.empty();

        List<PolicyChange> changes = changes(h, live);
        Map<String, Object> state = AuthorService.currentState(live);
        changes = changes.stream().filter(c -> AuthorService.referencesLiveState(c, state)).toList();
        if (changes.isEmpty() || !lintsClean(live, changes)) {
            log.info("Template author: nothing it can write for hypothesis {} ({})", h.hypothesisId(), h.lever());
            return Optional.empty();
        }
        PolicyChangeset.RiskTier tier = PolicyChangeset.RiskTier.valueOf(live.tier().name());
        String motivation = "Template author (no LLM): " + h.rationale();
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", live.name()), live.version(), changes, motivation, tier);
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, h.hypothesisId(), tier, ProposalStatus.DRAFT, null, null, ACTOR, Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null);
        proposals.insert(p);
        hypotheses.updateStatus(h.hypothesisId(), Hypothesis.Status.DRAFTED);
        if ("add_rule".equals(h.lever().get("action"))) draftPromptGuidance(h, live);
        log.info("Template author drafted {} from hypothesis {}: {}", p.proposalId(), h.hypothesisId(), changes);
        return Optional.of(p);
    }

    /** The changes a hypothesis states precisely enough to write without a model. */
    static List<PolicyChange> changes(Hypothesis h, DecisionDefinition live) {
        Map<String, Object> lever = h.lever();
        String action = str(lever.get("action"));
        String kind = str(lever.get("lever"));
        List<PolicyChange> out = new ArrayList<>();
        if ("add_rule".equals(action) && str(lever.get("when")) != null && str(lever.get("suggestedThen")) != null && !"true".equals(str(lever.get("when")))) {
            out.add(new PolicyChange.RuleAdd(live.name(), str(lever.get("when")), str(lever.get("suggestedThen")),
                "override_pattern: " + lever.getOrDefault("resolutions", "?") + " resolutions, " + pct(lever.get("agreement")) + " agreed"));
        } else if ("retire_rule".equals(action) && str(lever.get("ruleId")) != null) {
            out.add(new PolicyChange.RuleRetire(live.name(), str(lever.get("ruleId")), "retire_unused_rule"));
        } else if ("ruleAdd".equals(kind) && str(lever.get("addressToken")) != null) {
            String token = str(lever.get("addressToken")).replace("\\", "").replace("'", "");
            out.add(new PolicyChange.RuleAdd(live.name(), "has(input.address.token) && input.address.token == '" + token + "'", "true", "address_reuse_ring"));
        } else if ("ruleAdd".equals(kind) && h.scope() != null && str(h.scope().get("channel")) != null) {
            String channel = str(h.scope().get("channel")).replace("\\", "").replace("'", "");
            out.add(new PolicyChange.RuleAdd(live.name(), "input.order.channel == '" + channel + "'", "escalate", "channel_fraud_concentration"));
        }
        return out;
    }

    static boolean allowList(Hypothesis h) {
        return "allowListChange".equals(str(h.lever().get("lever"))) || "allow_list_change".equals(str(h.lever().get("action")));
    }

    /** An allow-list change is a config change ({@code config:<listName>}), T2: it widens or narrows what runs on its own. */
    private Optional<Proposal> draftAllowList(Hypothesis h) {
        String list = str(h.lever().get("listName")), entry = str(h.lever().get("entry"));
        boolean add = !Boolean.FALSE.equals(h.lever().get("add"));
        if (list == null || entry == null) return Optional.empty();
        if (add && NEVER_AUTONOMOUS.contains(entry.toLowerCase(Locale.ROOT))) {
            log.warn("Template author refused to add {} to allow-list {} (never-autonomous rule)", entry, list);
            return Optional.empty();
        }
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("config", list), 0, List.of(new PolicyChange.AllowListChange(list, entry, add)),
            "Template author (no LLM): " + h.rationale(), PolicyChangeset.RiskTier.T2);
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, h.hypothesisId(), PolicyChangeset.RiskTier.T2, ProposalStatus.DRAFT, null, null, ACTOR, Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null);
        proposals.insert(p);
        hypotheses.updateStatus(h.hypothesisId(), Hypothesis.Status.DRAFTED);
        return Optional.of(p);
    }

    /** DF-06: a draft prompt version for the main question that states the guidance people agreed on. */
    private void draftPromptGuidance(Hypothesis h, DecisionDefinition live) {
        try {
            var main = live.main().orElse(null);
            String question = live.questions().entrySet().stream().filter(e -> e.getValue() == main).map(Map.Entry::getKey).findFirst().orElse(null);
            if (main == null || question == null) return;
            String base = main.effectiveInstructions() == null ? "" : main.effectiveInstructions().strip();
            String guidance = "Reviewers agreed (" + h.lever().getOrDefault("resolutions", "several") + " cases, " + pct(h.lever().get("agreement")) + "): when "
                + h.lever().get("when") + ", the answer is \"" + h.lever().get("suggestedThen") + "\".";
            prompts.draft(live.name(), question, base + "\n" + guidance, "Template author from hypothesis " + h.hypothesisId(), null);
        } catch (RuntimeException e) {
            log.info("Template author: no prompt draft for {}: {}", h.hypothesisId(), e.getMessage());
        }
    }

    private boolean lintsClean(DecisionDefinition live, List<PolicyChange> changes) {
        try {
            var lint = studio.lint(ChangesetApplier.applyToDecision(live.yaml(), changes));
            if (!lint.ok()) log.warn("Template changeset for {} does not lint, refused: {}", live.name(), lint.issues());
            return lint.ok();
        } catch (RuntimeException e) {
            log.warn("Template changeset for {} cannot be applied, refused: {}", live.name(), e.getMessage());
            return false;
        }
    }

    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() || "null".equals(String.valueOf(o)) ? null : String.valueOf(o); }

    private static String pct(Object rate) { return rate instanceof Number n ? Math.round(n.doubleValue() * 100) + "%" : "most"; }
}
