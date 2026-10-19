package ai.intentoms.learning.api;

import ai.intentoms.learning.experiment.ExperimentManager;
import ai.intentoms.learning.experiment.LiftService;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** EX-02..EX-06 REST surface plus the XP-01 lift report; same {@code /api/v1/learning} base path as {@code LearningController}. */
@RestController
@RequestMapping("/api/v1/learning")
public class ExperimentController {
    private final ExperimentManager experiments;
    private final LiftService lift;
    private final ProposalRepository proposals;
    private final AuthorizationService authz;
    private ai.intentoms.learning.experiment.GuardrailReportService guardrailReport;

    @org.springframework.beans.factory.annotation.Autowired
    void setGuardrailReport(ai.intentoms.learning.experiment.GuardrailReportService guardrailReport) { this.guardrailReport = guardrailReport; }

    public ExperimentController(ExperimentManager experiments, LiftService lift, ProposalRepository proposals, AuthorizationService authz) {
        this.experiments = experiments;
        this.lift = lift;
        this.proposals = proposals;
        this.authz = authz;
    }

    public record StartRequest(String proposalId, ExperimentMode mode, double trafficShare, Map<String, Object> stopRule) {}
    public record StopRequest(ExperimentStatus status, Map<String, Object> result) {}

    /** Running experiments; {@code ?all=true} adds recently finished ones. */
    @GetMapping("/experiments")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<Experiment> list(@RequestParam(value = "all", defaultValue = "false") boolean all) {
        return all ? experiments.recent(100) : experiments.running();
    }

    /** One experiment with its proposal and a fresh measurement (shadow comparison or canary sequential test). */
    @GetMapping("/experiments/{id}")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> detail(@PathVariable String id) {
        Experiment e = experiments.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experiment", e);
        proposals.find(e.proposalId()).ifPresent(p -> out.put("proposal", Map.of("proposalId", p.proposalId(), "target", p.changeset().target().toString(),
            "status", p.status().name(), "tier", p.tier().name(), "changeTypes", p.changeset().changes().stream().map(c -> c.getClass().getSimpleName()).toList())));
        out.put("measurement", e.status() == ExperimentStatus.RUNNING ? experiments.measure(e) : e.result());
        return out;
    }

    @PostMapping("/experiments")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Experiment start(@RequestBody StartRequest body) {
        if (body == null || body.mode() == null || (body.mode() != ExperimentMode.HOLDOUT && body.proposalId() == null)) {
            throw new ValidationException("mode is required, and proposalId for shadow and canary");
        }
        return experiments.start(body.proposalId(), body.mode(), body.trafficShare(), body.stopRule(), actorRef());
    }

    @PostMapping("/experiments/{id}/complete")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Experiment complete(@PathVariable String id) {
        return experiments.complete(id, Actor.parse(actorRef()));
    }

    @PostMapping("/experiments/{id}/stop")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public void stop(@PathVariable String id, @RequestBody(required = false) StopRequest body) {
        experiments.stop(id, body == null ? null : body.status(), body == null ? null : body.result(), Actor.parse(actorRef()));
    }

    @PostMapping("/experiments/holdout")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Experiment ensureHoldout(@RequestParam(value = "share", defaultValue = "0.05") double share) {
        return experiments.ensureHoldout(share, actorRef());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ai.intentoms.learning.experiment.ExperimentTrafficRouter router;

    /**
     * EX-04: whether a customer is in the running persistent holdout. Membership is a sticky hash of the customer's vault
     * token (NF-07), which only the server can compute, so tools and people ask here instead of re-hashing the reference.
     */
    @GetMapping("/experiments/holdout/members/{customerRef}")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> holdoutMember(@PathVariable String customerRef) {
        Experiment holdout = experiments.running().stream().filter(e -> e.mode() == ExperimentMode.HOLDOUT).findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerRef", customerRef);
        out.put("experimentId", holdout == null ? null : holdout.experimentId());
        out.put("inHoldout", holdout != null && router != null && router.inHoldout("customer:" + customerRef));
        return out;
    }

    /** XP-01: cumulative lift against the persistent holdout, per metric, with confidence intervals. */
    @GetMapping("/lift")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> lift(@RequestParam(value = "days", defaultValue = "90") int days) {
        return lift.lift(days);
    }

    /** §12 AC-3: each guardrail with learning versus the holdout (or the weeks before) over the last {@code weeks}, with a verdict. */
    @GetMapping("/guardrails/report")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> guardrailReport(@RequestParam(value = "weeks", defaultValue = "4") int weeks) {
        return guardrailReport.report(weeks);
    }

    private String actorRef() { return authz.requirePrincipal().actorRef(); }
}
