package ai.intentoms.decision.api;

import ai.intentoms.decision.analytics.DecisionAnalyticsRepository;
import ai.intentoms.decision.definition.DecisionDefinitionRepository.Row;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.ImpactReport;
import ai.intentoms.decision.model.LintReport;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.provider.ProviderRegistry;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.decision.studio.CalibrationMonitor;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Studio and definition endpoints under {@code /api/v1/decisions} (ARCHITECTURE section 3.3 REST). */
@RestController
@RequestMapping("/api/v1/decisions")
public class DecisionDefinitionController {
    private final DecisionStudioService studio;
    private final DecisionService decisions;
    private final DecisionAnalyticsRepository analytics;
    private final CalibrationMonitor calibration;
    private final ProviderRegistry providers;
    private final AuthorizationService authz;

    public DecisionDefinitionController(DecisionStudioService studio, DecisionService decisions, DecisionAnalyticsRepository analytics, CalibrationMonitor calibration,
                                        ProviderRegistry providers, AuthorizationService authz) {
        this.studio = studio;
        this.decisions = decisions;
        this.analytics = analytics;
        this.calibration = calibration;
        this.providers = providers;
        this.authz = authz;
    }

    public record DraftRequest(String yaml) {}
    public record LintRequest(String yaml) {}
    public record ImpactRequest(Integer days, Integer limit) {}
    public record SubmitRequest(String reason, String ticket) {}
    public record PublishRequest(String rollout, Boolean emergency) {}
    public record KillSwitchRequest(boolean on) {}
    public record EvaluateNowRequest(Map<String, Object> input, String subjectType, String subjectId, String mode, Integer version) {}

    @GetMapping
    @RequiresPermission(Permissions.DECISION_READ)
    public List<Map<String, Object>> list() { return studio.summaries(); }

    @GetMapping("/providers")
    @RequiresPermission(Permissions.DECISION_READ)
    public Map<String, Boolean> providers() { return providers.availability(); }

    @GetMapping("/{name}")
    @RequiresPermission(Permissions.DECISION_READ)
    public List<Map<String, Object>> versions(@PathVariable String name) { return studio.versions(name).stream().map(DecisionDefinitionController::view).toList(); }

    @GetMapping("/{name}/versions/{version}")
    @RequiresPermission(Permissions.DECISION_READ)
    public Map<String, Object> version(@PathVariable String name, @PathVariable int version) { return view(studio.get(name, version)); }

    @PostMapping("/{name}/drafts")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public DecisionDefinition createDraft(@PathVariable String name, @RequestBody DraftRequest body) { return studio.createDraft(name, body.yaml()); }

    @PostMapping("/{name}/versions/{version}/yaml")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public DecisionDefinition updateDraft(@PathVariable String name, @PathVariable int version, @RequestBody DraftRequest body) { return studio.updateDraft(name, version, body.yaml()); }

    @PatchMapping("/{name}/versions/{version}")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public DecisionDefinition edit(@PathVariable String name, @PathVariable int version, @RequestBody Map<String, Object> edits) { return studio.edit(name, version, edits); }

    @PostMapping("/{name}/lint")
    @RequiresPermission(Permissions.DECISION_READ)
    public LintReport lintYaml(@PathVariable String name, @RequestBody LintRequest body) { return studio.lint(body.yaml()); }

    @PostMapping("/{name}/versions/{version}/lint")
    @RequiresPermission(Permissions.DECISION_READ)
    public LintReport lint(@PathVariable String name, @PathVariable int version) { return studio.lint(name, version); }

    @PostMapping("/{name}/versions/{version}/impact")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public ImpactReport impact(@PathVariable String name, @PathVariable int version, @RequestBody(required = false) ImpactRequest body) {
        int days = body == null || body.days() == null ? 7 : body.days();
        int limit = body == null || body.limit() == null ? DecisionStudioService.IMPACT_LIMIT : body.limit();
        return studio.impactDiff(name, version, days, limit);
    }

    @PostMapping("/{name}/versions/{version}/evaluate")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public EvaluationReport evaluate(@PathVariable String name, @PathVariable int version) { return studio.evaluate(name, version); }

    @PostMapping("/{name}/versions/{version}/submit")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public Map<String, Object> submit(@PathVariable String name, @PathVariable int version, @RequestBody SubmitRequest body) { return view(studio.submitForApproval(name, version, body.reason(), body.ticket())); }

    @PostMapping("/{name}/versions/{version}/approve")
    @RequiresPermission(Permissions.DECISION_APPROVE)
    public Map<String, Object> approve(@PathVariable String name, @PathVariable int version) { return view(studio.approve(name, version)); }

    @PostMapping("/{name}/versions/{version}/publish")
    @RequiresPermission(Permissions.DECISION_PUBLISH)
    public Map<String, Object> publish(@PathVariable String name, @PathVariable int version, @RequestBody PublishRequest body) {
        return view(studio.publish(name, version, body.rollout(), Boolean.TRUE.equals(body.emergency())));
    }

    @PostMapping("/{name}/rollback")
    @RequiresPermission(Permissions.DECISION_PUBLISH)
    public Map<String, Object> rollback(@PathVariable String name) { return view(studio.rollback(name)); }

    @PostMapping("/{name}/kill-switch")
    @RequiresPermission(Permissions.DECISION_PUBLISH)
    public Map<String, Object> killSwitch(@PathVariable String name, @RequestBody KillSwitchRequest body) {
        studio.killSwitch(name, body.on());
        return Map.of("decision", name, "killSwitch", body.on());
    }

    /** Ad-hoc evaluation with an explicit input; SIMULATION by default never acts or escalates. */
    @PostMapping("/{name}/evaluate-now")
    @RequiresPermission(Permissions.DECISION_READ)
    public DecisionResult evaluateNow(@PathVariable String name, @RequestBody EvaluateNowRequest body) {
        Mode mode = body.mode() == null ? Mode.SIMULATION : Mode.valueOf(body.mode().toUpperCase());
        DecisionOptions opts = new DecisionOptions(body.subjectType(), body.subjectId(), null, mode, body.version(), Actor.parse(authz.actorRef()), Map.of());
        return decisions.decide(name, body.input() == null ? Map.of() : body.input(), opts);
    }

    @GetMapping("/{name}/analytics")
    @RequiresPermission(Permissions.DECISION_READ)
    public Map<String, Object> analytics(@PathVariable String name, @RequestParam(defaultValue = "30") int days) {
        return Map.of("decision", name, "versions", analytics.perVersion(name), "daily", analytics.daily(name, days));
    }

    @PostMapping("/{name}/versions/{version}/drift-check")
    @RequiresPermission(Permissions.DECISION_READ)
    public Object driftCheck(@PathVariable String name, @PathVariable int version) { return calibration.check(name, version); }

    static Map<String, Object> view(Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", row.definition());
        m.put("createdBy", row.createdBy());
        m.put("updatedAt", row.updatedAt());
        m.put("submittedBy", row.submittedBy());
        m.put("submittedAt", row.submittedAt());
        m.put("publishedAt", row.publishedAt());
        m.put("retiredAt", row.retiredAt());
        m.put("approvals", row.approvals());
        m.put("rollout", row.rollout());
        m.put("evaluationReport", row.evaluationReport());
        m.put("lintReport", row.lintReport());
        m.put("retroReviewDueAt", row.retroReviewDueAt());
        return m;
    }
}
