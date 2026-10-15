package ai.intentoms.copilot.change;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.Untrusted;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.definition.DefinitionEditor;
import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.time.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration copilot (FR-CFG-01, FR-CFG-02, FR-SM-12, FR-WF-03, UC-10, UC-11): turns a plain-language request into a
 * validated {@link ChangeSet}, stages selected changes as drafts only (decision drafts in the studio, everything else as
 * {@link DraftArtifact}s) and never publishes (GR-14). Model output is typed by a strict schema (GR-04), every artefact
 * is re-validated deterministically, one automatic repair round-trip feeds validator messages back to the model, and
 * untrusted request text is framed as data (GR-08).
 */
@Service
public class ConfigCopilotService {
    private static final Logger log = LoggerFactory.getLogger(ConfigCopilotService.class);
    public static final String EVENT_PROPOSED = "copilot.changeset_proposed";
    public static final String EVENT_STAGED = "copilot.changeset_staged";
    public static final String EVENT_REJECTED = "copilot.changeset_rejected";

    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final CatalogueContextBuilder catalogue;
    private final ArtifactValidator validator;
    private final ChangeSetRepository repo;
    private final DraftArtifactService drafts;
    private final DecisionStudioService studio;
    private final DecisionDefinitionParser parser;
    private final GoldenCaseRepository golden;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final EventStore events;
    private final TimeSource time;

    public ConfigCopilotService(CopilotLlmClient llm, PromptTemplates templates, CatalogueContextBuilder catalogue, ArtifactValidator validator, ChangeSetRepository repo,
                                DraftArtifactService drafts, DecisionStudioService studio, DecisionDefinitionParser parser, GoldenCaseRepository golden, AuthorizationService authz,
                                AuditLog audit, EventStore events, TimeSource time) {
        this.llm = llm;
        this.templates = templates;
        this.catalogue = catalogue;
        this.validator = validator;
        this.repo = repo;
        this.drafts = drafts;
        this.studio = studio;
        this.parser = parser;
        this.golden = golden;
        this.authz = authz;
        this.audit = audit;
        this.events = events;
        this.time = time;
    }

    // ------------------------------------------------------------------ propose

    /** Asks the model for a ChangeSet, validates and repairs it, persists it as {@code proposed}. */
    public ChangeSet propose(ChangeRequest request) {
        requireProposer();
        if (!llm.available()) throw new OmsException(HttpStatus.SERVICE_UNAVAILABLE, "copilot_unavailable", "The configuration copilot needs a model key (oms.copilot.api-key); none is configured");
        Map<String, Object> ctx = catalogue.build(request.scope());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request", Untrusted.text("business user", request.text()));
        payload.put("scope", request.scope() == null ? "" : request.scope());
        payload.put("context", request.context());
        Object answers = request.context().get("answers");
        if (answers != null) payload.put("clarifications", Untrusted.text("business user answers to the copilot's questions", Json.write(answers)));
        Object previousId = request.context().get("previousChangeSetId");
        if (previousId != null) repo.find(String.valueOf(previousId)).ifPresent(prev -> payload.put("previousProposal", Map.of("summary", prev.summary(), "questions", prev.questions(),
            "changes", prev.changes().stream().map(c -> Map.of("id", c.id(), "kind", c.kind().name(), "target", c.target(), "after", c.after(), "validationIssues", c.validation().issues())).toList())));
        payload.put("catalogue", ctx);
        JsonNode out = llm.complete("config", templates.get("system-config.md") + "\n\n# Artefact formats\n" + catalogue.formats(), payload, ChangeSetSchema.PROPOSAL);
        List<ProposedChange> changes = toChanges(out.path("changes"));
        return proposeFromChanges(request, out.path("summary").asText(""), strings(out.path("assumptions")), strings(out.path("questions")), changes, "change", Map.of("model", llm.model()));
    }

    /**
     * Validation + repair + persistence for changes that already exist as structured proposals (the LLM proposal or the
     * bootstrap copilot's blueprint). Public for the bootstrap copilot.
     */
    public ChangeSet proposeFromChanges(ChangeRequest request, String summary, List<String> assumptions, List<String> questions, List<ProposedChange> changes, String source, Map<String, Object> meta) {
        requireProposer();
        if (changes.isEmpty()) throw new ValidationException("The copilot could not derive any concrete change from the request" + (questions.isEmpty() ? "" : "; it asks: " + String.join(" | ", questions)),
            Map.of("summary", summary, "questions", questions, "assumptions", assumptions));
        List<ProposedChange> validated = validateAll(changes);
        boolean repaired = false;
        if (validated.stream().anyMatch(c -> !c.validation().ok()) && llm.available()) {
            validated = repair(request, validated);
            repaired = true;
        }
        String id = Ids.prefixed("cps");
        String by = authz.actorRef();
        Map<String, Object> m = new LinkedHashMap<>(meta);
        m.put("repairRound", repaired);
        m.put("validChanges", validated.stream().filter(c -> c.validation().ok()).count());
        m.put("invalidChanges", validated.stream().filter(c -> !c.validation().ok()).count());
        ChangeSet cs = new ChangeSet(id, request, summary, assumptions, questions, validated, by, time.now(), "proposed", source, m);
        repo.insert(cs);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changesetId", id);
        payload.put("source", source);
        payload.put("summary", summary);
        payload.put("changes", validated.stream().map(c -> Map.of("id", c.id(), "kind", c.kind().name(), "target", c.target(), "valid", c.validation().ok())).toList());
        events.append("config", "copilot:" + id, new NewEvent(EVENT_PROPOSED, payload, Actor.parse(by)));
        audit.record(EVENT_PROPOSED, "copilot_changeset", id, request.text(), Map.of("changes", validated.size(), "source", source));
        return cs;
    }

    List<ProposedChange> validateAll(List<ProposedChange> changes) {
        Set<String> proposedTargets = new HashSet<>();
        for (ProposedChange c : changes) proposedTargets.add((c.kind().isDecision() ? "decision" : c.kind().name()) + ":" + c.target());
        List<ProposedChange> out = new ArrayList<>();
        for (ProposedChange c : changes) {
            String before = catalogue.current(c.kind(), c.target()).orElse(null);
            ValidationResult v = validator.validate(c.kind(), c.target(), c.after(), proposedTargets);
            String diff = UnifiedDiff.of(before, c.after(), c.kind().name() + "/" + c.target());
            out.add(c.withValidation(before, v, diff, approvals(c)));
        }
        return out;
    }

    private List<ProposedChange> repair(ChangeRequest request, List<ProposedChange> changes) {
        List<Map<String, Object>> failing = new ArrayList<>();
        for (ProposedChange c : changes) {
            if (c.validation().ok()) continue;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", c.id());
            f.put("kind", c.kind().name());
            f.put("target", c.target());
            f.put("after", c.after());
            f.put("validatorMessages", c.validation().issues());
            if (c.before() != null) f.put("currentArtefact", c.before());
            failing.add(f);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request", Untrusted.text("business user", request.text()));
        payload.put("failingChanges", failing);
        payload.put("formats", catalogue.formats());
        Map<String, String> fixed = new LinkedHashMap<>();
        try {
            JsonNode out = llm.complete("repair", templates.get("system-repair.md"), payload, ChangeSetSchema.REPAIR);
            for (JsonNode n : out.path("changes")) fixed.put(n.path("id").asText(), n.path("after").asText());
        } catch (OmsException e) {
            log.warn("copilot repair round failed: {}", e.getMessage());
            return changes;
        }
        List<ProposedChange> patched = new ArrayList<>();
        for (ProposedChange c : changes) patched.add(!c.validation().ok() && fixed.containsKey(c.id()) ? c.withAfter(fixed.get(c.id()), true) : c);
        return validateAll(patched);
    }

    private int approvals(ProposedChange c) {
        if (c.kind().isDecision()) {
            DecisionDefinitionParser.Parsed p = parser.parseLenient(c.after());
            return p.definition() == null ? 1 : p.definition().tier().requiredApprovers();
        }
        return 1;
    }

    // ------------------------------------------------------------------ reads

    public ChangeSet get(String id) { return repo.find(id).orElseThrow(() -> new NotFoundException("Change set " + id + " not found")); }

    public Page<ChangeSet> list(String status, int offset, int limit) { return repo.list(status, offset, limit); }

    // ------------------------------------------------------------------ stage / reject (FR-CFG-02)

    /** Stages the selected (or all valid, when {@code changeIds} is empty) changes as drafts. Never publishes. */
    public ChangeSet stage(String changesetId, List<String> changeIds) {
        requireProposer();
        ChangeSet cs = get(changesetId);
        if ("rejected".equals(cs.status())) throw new ConflictException("Change set " + changesetId + " was rejected");
        Set<String> selected = changeIds == null || changeIds.isEmpty() ? null : new HashSet<>(changeIds);
        if (selected != null) for (String id : selected) cs.change(id);
        List<String> invalidSelected = cs.changes().stream().filter(c -> selected == null || selected.contains(c.id())).filter(c -> !c.validation().ok()).map(ProposedChange::id).toList();
        if (selected != null && !invalidSelected.isEmpty()) throw new ValidationException("Changes " + invalidSelected + " failed validation and cannot be staged", Map.of("changeIds", invalidSelected));
        List<ProposedChange> out = new ArrayList<>();
        int staged = 0;
        Map<String, List<ProposedChange>> decisionGroups = new LinkedHashMap<>();
        for (ProposedChange c : cs.changes()) {
            boolean pick = (selected == null || selected.contains(c.id())) && c.validation().ok() && c.staged() == null;
            if (pick && c.kind().isDecision()) decisionGroups.computeIfAbsent(c.target(), k -> new ArrayList<>()).add(c);
        }
        Map<String, Map<String, Object>> decisionStaged = new LinkedHashMap<>();
        Map<String, String> decisionErrors = new LinkedHashMap<>();
        for (var g : decisionGroups.entrySet()) {
            try { decisionStaged.put(g.getKey(), stageDecision(cs, g.getKey(), g.getValue())); }
            catch (OmsException e) { decisionErrors.put(g.getKey(), e.getMessage()); }
        }
        for (ProposedChange c : cs.changes()) {
            boolean pick = (selected == null || selected.contains(c.id())) && c.validation().ok() && c.staged() == null;
            if (!pick) { out.add(c); continue; }
            if (c.kind().isDecision()) {
                Map<String, Object> s = decisionStaged.get(c.target());
                if (s != null) { out.add(c.withStaged(s, null)); staged++; } else out.add(c.withStaged(null, decisionErrors.get(c.target())));
                continue;
            }
            try {
                out.add(c.withStaged(stageArtifact(cs, c), null));
                staged++;
            } catch (OmsException e) {
                out.add(c.withStaged(null, e.getMessage()));
            }
        }
        ChangeSet updated = cs.withChanges(out, "staged").withStatus("staged", Map.of("stagedAt", time.now().toString(), "stagedBy", authz.actorRef()));
        repo.update(updated);
        String by = authz.actorRef();
        events.append("config", "copilot:" + cs.id(), new NewEvent(EVENT_STAGED, Map.of("changesetId", cs.id(), "staged", staged,
            "changes", out.stream().filter(c -> c.staged() != null).map(c -> Map.of("id", c.id(), "kind", c.kind().name(), "target", c.target(), "staged", c.staged())).toList()), Actor.parse(by)));
        audit.record(EVENT_STAGED, "copilot_changeset", cs.id(), cs.request().text(), Map.of("staged", staged));
        return updated;
    }

    /** One studio draft per decision: several changes for the same decision are merged ({@link DecisionYamlMerge}). */
    private Map<String, Object> stageDecision(ChangeSet cs, String decision, List<ProposedChange> group) {
        String merged = DecisionYamlMerge.merge(group.get(0).before(), group.stream().map(ProposedChange::after).toList());
        String yaml = DefinitionEditor.apply(merged, Map.of("changeReason", "copilot: " + cs.request().text(), "ticket", "copilot:" + cs.id()));
        DecisionDefinition draft = studio.createDraft(decision, yaml);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "decision_draft");
        s.put("ref", draft.name() + "@" + draft.version());
        s.put("name", draft.name());
        s.put("version", draft.version());
        s.put("status", draft.status());
        if (group.size() > 1) { s.put("merged", true); s.put("mergedChangeIds", group.stream().map(ProposedChange::id).toList()); }
        return s;
    }

    private Map<String, Object> stageArtifact(ChangeSet cs, ProposedChange c) {
        DraftArtifact a = drafts.stage(cs.id(), c);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "artifact");
        s.put("ref", a.id());
        s.put("artifactId", a.id());
        s.put("applyPermission", DraftArtifactService.requiredPermission(c.kind()));
        return s;
    }

    public ChangeSet reject(String changesetId, String reason) {
        requireProposer();
        if (reason == null || reason.isBlank()) throw new ValidationException("A rejection reason is required");
        ChangeSet cs = get(changesetId);
        if ("staged".equals(cs.status())) throw new ConflictException("Change set " + changesetId + " is already staged; discard its drafts individually");
        ChangeSet updated = cs.withStatus("rejected", Map.of("rejectedAt", time.now().toString(), "rejectedBy", authz.actorRef(), "rejectReason", reason));
        repo.update(updated);
        events.append("config", "copilot:" + cs.id(), new NewEvent(EVENT_REJECTED, Map.of("changesetId", cs.id(), "reason", reason), Actor.parse(authz.actorRef())));
        audit.record(EVENT_REJECTED, "copilot_changeset", cs.id(), reason, Map.of());
        return updated;
    }

    // ------------------------------------------------------------------ tests and explanations

    /** Writes the generated golden cases of a decision change into its golden set (GR-11). */
    public Map<String, Object> addTestsToGoldenSet(String changesetId, String changeId) {
        authz.require(Permissions.DECISION_EDIT);
        ChangeSet cs = get(changesetId);
        ProposedChange c = cs.change(changeId);
        if (!c.kind().isDecision()) throw new ValidationException("Only decision changes carry golden cases");
        if (c.tests().isEmpty()) throw new ValidationException("Change " + changeId + " has no generated tests");
        DecisionDefinitionParser.Parsed p = parser.parseLenient(c.after());
        String set = p.definition() != null && p.definition().evaluation() != null ? p.definition().evaluation().goldenSet() : c.target() + "_gold";
        List<GoldenCase> cases = new ArrayList<>();
        String prefix = "cp_" + changeId + "_";
        for (GeneratedTest t : c.tests()) cases.add(new GoldenCase(set, prefix + t.caseId(), t.input(), t.expected(), t.notes() == null ? "generated by copilot for " + cs.id() : t.notes()));
        golden.upsertAll(cases);
        audit.record("copilot.golden_cases_added", "golden_set", set, cs.request().text(), Map.of("changesetId", cs.id(), "changeId", changeId, "cases", cases.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goldenSet", set);
        out.put("added", cases.size());
        out.put("caseIds", cases.stream().map(GoldenCase::caseId).toList());
        out.put("total", golden.count(set));
        return out;
    }

    /** Plain-language explanation of one change's diff (model when available, deterministic otherwise). */
    public Map<String, Object> explainChange(String changesetId, String changeId) {
        ChangeSet cs = get(changesetId);
        ProposedChange c = cs.change(changeId);
        int[] stats = UnifiedDiff.stats(c.before(), c.after());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeId", changeId);
        out.put("kind", c.kind().name());
        out.put("target", c.target());
        out.put("linesAdded", stats[0]);
        out.put("linesRemoved", stats[1]);
        if (llm.available()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("request", Untrusted.text("business user", cs.request().text()));
                payload.put("kind", c.kind().name());
                payload.put("target", c.target());
                payload.put("rationale", c.rationale());
                payload.put("diff", c.diff());
                payload.put("validation", c.validation());
                JsonNode n = llm.complete("explain_change", templates.get("system-explain-change.md"), payload, ChangeSetSchema.EXPLAIN);
                out.put("summary", n.path("summary").asText());
                out.put("impacts", strings(n.path("impacts")));
                out.put("risks", strings(n.path("risks")));
                out.put("generated", true);
                out.put("model", llm.model());
                return out;
            } catch (OmsException e) { log.warn("explainChange fell back to the deterministic summary: {}", e.getMessage()); }
        }
        out.put("summary", deterministicExplanation(c, stats));
        out.put("impacts", List.of(c.kind().isDecision() ? "Decision '" + c.target() + "' gets a new draft version that must pass lint, evaluation and approval before publishing"
            : "Artefact '" + c.target() + "' is staged as a draft until a person with " + DraftArtifactService.requiredPermission(c.kind()) + " applies it"));
        out.put("risks", c.validation().warnings());
        out.put("generated", false);
        return out;
    }

    private static String deterministicExplanation(ProposedChange c, int[] stats) {
        return (c.before() == null ? "Creates a new " : "Changes the ") + c.kind().name().replace('_', ' ') + " '" + c.target() + "' (" + stats[0] + " lines added, " + stats[1] + " removed). "
            + (c.rationale() == null ? "" : c.rationale() + " ") + (c.validation().ok() ? "Validation passed." : "Validation found " + c.validation().issues().size() + " issue(s).");
    }

    // ------------------------------------------------------------------ helpers

    private List<ProposedChange> toChanges(JsonNode arr) {
        List<ProposedChange> out = new ArrayList<>();
        int i = 1;
        for (JsonNode n : arr) {
            String id = n.path("id").asText("").isBlank() ? "c" + i : n.path("id").asText();
            ChangeKind kind = ChangeKind.parse(n.path("kind").asText());
            List<GeneratedTest> tests = new ArrayList<>();
            for (JsonNode t : n.path("tests")) {
                Map<String, Object> input = safeMap(t.path("input").asText("{}"));
                Map<String, Object> expected = safeMap(t.path("expected").asText("{}"));
                if (!expected.containsKey("value") && expected.size() == 1) expected = new LinkedHashMap<>(Map.of("value", expected.values().iterator().next()));
                tests.add(new GeneratedTest(t.path("caseId").asText("case_" + tests.size()), input, expected, t.path("notes").asText(null)));
            }
            out.add(new ProposedChange(id, kind, n.path("target").asText(), n.path("rationale").asText(), null, n.path("after").asText(), null, ValidationResult.passed(), tests,
                n.path("riskTier").asText("T1"), 1, false, null, null));
            i++;
        }
        return out;
    }

    private static Map<String, Object> safeMap(String json) {
        try { return Json.readMap(json); } catch (IllegalArgumentException e) { return new LinkedHashMap<>(Map.of("raw", json)); }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asText());
        return out;
    }

    /** decision.edit, statemodel.edit or config.admin, and a human principal (GR-14). */
    private void requireProposer() {
        Principal p = authz.requirePrincipal();
        if (p.type() == Principal.Type.DECISION || p.type() == Principal.Type.AGENT) throw new ForbiddenException("Only people may use the configuration copilot (GR-14)");
        if (!(p.has(Permissions.DECISION_EDIT) || p.has(Permissions.STATEMODEL_EDIT) || p.has(Permissions.CONFIG_ADMIN)))
            throw new ForbiddenException("Missing permission decision.edit | statemodel.edit | config.admin");
    }
}
