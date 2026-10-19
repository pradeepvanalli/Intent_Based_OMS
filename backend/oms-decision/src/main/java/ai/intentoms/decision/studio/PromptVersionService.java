package ai.intentoms.decision.studio;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DefinitionEditor;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.LintReport;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DF-06: versioned prompt artifacts behind the same gate as decision versions. A new prompt text for one question is
 * drafted, linted (its own checks plus the decision linter on the resulting YAML), evaluated against the decision's
 * golden set through a studio draft that carries it, submitted, approved by someone other than its author, and
 * published through {@link DecisionStudioService#publish}. Nothing here bypasses the studio: the prompt reaches the
 * model only as part of a published decision version.
 */
@Service
public class PromptVersionService {
    static final int MAX_LENGTH = 4000;
    static final List<String> INJECTION = List.of("ignore previous", "ignore all previous", "disregard the", "system prompt", "you are now",
        "developer mode", "jailbreak", "do not follow");

    private final PromptVersionRepository prompts;
    private final DecisionStudioService studio;
    private final DecisionDefinitionRepository definitions;
    private final AuthorizationService authz;
    private final TimeSource time;

    public PromptVersionService(PromptVersionRepository prompts, DecisionStudioService studio, DecisionDefinitionRepository definitions, AuthorizationService authz, TimeSource time) {
        this.prompts = prompts;
        this.studio = studio;
        this.definitions = definitions;
        this.authz = authz;
        this.time = time;
    }

    public List<PromptVersion> list(String decision) { return prompts.forDecision(decision); }

    public PromptVersion get(String decision, String question, int version) { return prompts.get(decision, question, version); }

    public PromptVersion draft(String decision, String question, String text, String reason, String ticket) {
        return draft(decision, question, text, reason, ticket, null);
    }

    /**
     * DF-06: a draft written by the system (the template or LLM author) is attributed to that system actor
     * ({@code createdBy}), never to the person whose request happened to trigger the run; null means the caller.
     */
    public PromptVersion draft(String decision, String question, String text, String reason, String ticket, String createdBy) {
        DecisionDefinition live = live(decision);
        if (!live.questions().containsKey(question)) throw new ValidationException("Decision " + decision + " has no question '" + question + "'");
        if (text == null || text.isBlank()) throw new ValidationException("Prompt text is required");
        int v = prompts.nextVersion(decision, question);
        var now = time.now();
        prompts.insert(new PromptVersion(decision, question, v, text.strip(), "draft", reason, ticket, null, null, null, createdBy == null ? authz.actorRef() : createdBy, now, now, null));
        return prompts.get(decision, question, v);
    }

    /** The prompt's own checks plus the decision linter on the YAML it would produce. */
    public Map<String, Object> lint(String decision, String question, int version) {
        PromptVersion p = prompts.get(decision, question, version);
        DecisionDefinition live = live(decision);
        List<Map<String, Object>> issues = new ArrayList<>(ownIssues(p.text()));
        LintReport yamlLint = studio.lint(candidateYaml(live, question, p.text()));
        for (var i : yamlLint.issues()) issues.add(Json.toMap(i));
        boolean ok = issues.stream().noneMatch(i -> "ERROR".equalsIgnoreCase(String.valueOf(i.get("severity"))));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", ok);
        report.put("issues", issues);
        report.put("baseVersion", live.version());
        prompts.setLint(decision, question, version, report);
        return report;
    }

    /** Lints, then evaluates the decision's golden set on a studio draft that carries this prompt. */
    public PromptVersion evaluate(String decision, String question, int version) {
        PromptVersion p = requireStatus(decision, question, version, "draft", "evaluated");
        Map<String, Object> lint = lint(decision, question, version);
        if (!Boolean.TRUE.equals(lint.get("ok"))) throw new ValidationException("Prompt version " + version + " does not lint: " + lint.get("issues"));
        DecisionDefinition live = live(decision);
        DecisionDefinition draft = studio.createDraft(decision, candidateYaml(live, question, p.text()));
        EvaluationReport report = studio.evaluate(decision, draft.version());
        Map<String, Object> r = new LinkedHashMap<>(Json.toMap(report));
        r.put("baseVersion", live.version());
        prompts.setEvaluation(decision, question, version, draft.version(), r, report.passed() ? "evaluated" : "draft");
        return prompts.get(decision, question, version);
    }

    public PromptVersion submit(String decision, String question, int version, String reason, String ticket) {
        PromptVersion p = requireStatus(decision, question, version, "evaluated");
        studio.submitForApproval(decision, p.studioVersion(), reason == null ? p.changeReason() : reason, ticket == null ? p.ticket() : ticket);
        prompts.setStatus(decision, question, version, "pending_approval");
        return prompts.get(decision, question, version);
    }

    /** The prompt's author can never approve it, matching the studio's submitter rule (GR-14). */
    public PromptVersion approve(String decision, String question, int version) {
        PromptVersion p = requireStatus(decision, question, version, "pending_approval");
        if (authz.actorRef().equals(p.createdBy())) throw new ForbiddenException("The author of a prompt version cannot approve it");
        var row = studio.approve(decision, p.studioVersion());
        prompts.setStatus(decision, question, version, "approved".equals(row.definition().status()) ? "approved" : "pending_approval");
        return prompts.get(decision, question, version);
    }

    public PromptVersion publish(String decision, String question, int version, String rollout) {
        PromptVersion p = requireStatus(decision, question, version, "approved");
        Object base = p.evaluationReport() == null ? null : p.evaluationReport().get("baseVersion");
        int liveNow = live(decision).version();
        if (base instanceof Number n && n.intValue() != liveNow) {
            throw new ConflictException("Decision " + decision + " changed (live version " + liveNow + ", evaluated against " + n.intValue() + "); draft and evaluate the prompt again");
        }
        studio.publish(decision, p.studioVersion(), rollout == null || rollout.isBlank() ? "live" : rollout, false);
        prompts.markPublished(decision, question, version, time.now());
        return prompts.get(decision, question, version);
    }

    public PromptVersion reject(String decision, String question, int version) {
        requireStatus(decision, question, version, "draft", "evaluated", "pending_approval", "approved");
        prompts.setStatus(decision, question, version, "rejected");
        return prompts.get(decision, question, version);
    }

    static List<Map<String, Object>> ownIssues(String text) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String t = text == null ? "" : text;
        if (t.isBlank()) issues.add(issue("ERROR", "prompt_empty", "Prompt text is empty"));
        if (t.length() > MAX_LENGTH) issues.add(issue("ERROR", "prompt_too_long", "Prompt is " + t.length() + " characters; the limit is " + MAX_LENGTH));
        if (t.contains("{{") || t.contains("${")) issues.add(issue("ERROR", "prompt_placeholder", "Prompt contains an unrendered template placeholder"));
        String lower = t.toLowerCase(Locale.ROOT);
        for (String phrase : INJECTION) if (lower.contains(phrase)) issues.add(issue("ERROR", "prompt_injection_phrase", "Prompt contains \"" + phrase + "\", which reads as a prompt-injection instruction"));
        return issues;
    }

    private static Map<String, Object> issue(String severity, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    private static String candidateYaml(DecisionDefinition live, String question, String text) {
        QuestionDef q = live.questions().get(question);
        String field = q != null && "noul".equals(q.type()) ? "statement" : "instructions";
        return DefinitionEditor.apply(live.yaml(), Map.of("questions." + question + "." + field, text));
    }

    private PromptVersion requireStatus(String decision, String question, int version, String... allowed) {
        PromptVersion p = prompts.get(decision, question, version);
        for (String a : allowed) if (a.equals(p.status())) return p;
        throw new ConflictException("Prompt version " + version + " is " + p.status() + "; expected " + String.join(" or ", allowed));
    }

    private DecisionDefinition live(String decision) {
        return definitions.byStatus(decision, "live").stream().findFirst().orElseThrow(() -> new NotFoundException("Decision " + decision + " has no live version")).definition();
    }
}
