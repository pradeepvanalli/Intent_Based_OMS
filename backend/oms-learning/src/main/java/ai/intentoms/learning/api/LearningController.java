package ai.intentoms.learning.api;

import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SOO REST surface (requirements doc §9.3), under the existing {@code /api/v1} prefix like every other module.
 * This covers the Phase 0 "Instrument" + governance read/write endpoints (learners, facts, objective, kill switch)
 * and the proposal/experiment read endpoints. Proposal lifecycle actions (evaluate/approve/reject), experiment
 * start/inspect, and the cumulative-lift report are added here by the evaluator and experiment-manager builds —
 * extend this controller rather than adding a second one under the same base path.
 */
@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {
    private final LearnerRepository learners;
    private final LearnedFactRepository facts;
    private final ProposalRepository proposals;
    private final ObjectiveProfileRepository objectives;
    private final AuthorizationService authz;
    private final ai.intentoms.learning.governance.LearnerGovernance governance;
    private final ai.intentoms.learning.governance.LearnerRegistry registry;
    private final ai.intentoms.learning.governance.LiveParameterRepository liveParameters;
    private final ai.intentoms.learning.service.CoverageMonitor coverage;
    private final ai.intentoms.platform.config.TenantConfigStore config;
    private final List<ai.intentoms.learning.governance.GovernedLearner> learnerBeans;
    private final ai.intentoms.learning.service.RewardMaturityService rewardMaturity;
    private final ai.intentoms.learning.promotion.PromotionService promotion;
    private final ai.intentoms.learning.promotion.GuardrailBreachMonitor guardrails;
    private final ai.intentoms.learning.service.ObjectiveService objectiveService;
    private final ai.intentoms.learning.event.LearningEvents events;

    public LearningController(LearnerRepository learners, LearnedFactRepository facts, ProposalRepository proposals, ObjectiveProfileRepository objectives, AuthorizationService authz,
                              ai.intentoms.learning.governance.LearnerGovernance governance, ai.intentoms.learning.governance.LearnerRegistry registry,
                              ai.intentoms.learning.governance.LiveParameterRepository liveParameters, ai.intentoms.learning.service.CoverageMonitor coverage,
                              ai.intentoms.platform.config.TenantConfigStore config, List<ai.intentoms.learning.governance.GovernedLearner> learnerBeans,
                              ai.intentoms.learning.service.RewardMaturityService rewardMaturity, ai.intentoms.learning.promotion.PromotionService promotion,
                              ai.intentoms.learning.promotion.GuardrailBreachMonitor guardrails, ai.intentoms.learning.service.ObjectiveService objectiveService,
                              ai.intentoms.learning.event.LearningEvents events) {
        this.objectiveService = objectiveService;
        this.events = events;
        this.learnerBeans = learnerBeans;
        this.rewardMaturity = rewardMaturity;
        this.promotion = promotion;
        this.guardrails = guardrails;
        this.governance = governance;
        this.registry = registry;
        this.liveParameters = liveParameters;
        this.coverage = coverage;
        this.config = config;
        this.learners = learners;
        this.facts = facts;
        this.proposals = proposals;
        this.objectives = objectives;
        this.authz = authz;
    }

    public record AutonomyUpdate(AutonomyLevel autonomyLevel) {}
    public record ObjectiveUpdate(ObjectiveWeights weights, Map<String, Object> constraints) {}
    /** {@code learnerKey} empty or "*" means every learner for this tenant (GV-03 "pause all learning"). */
    public record KillSwitch(String learnerKey, boolean enabled) {}
    public record KillSwitchState(boolean globalKillSwitch, boolean tenantPaused, List<String> pausedLearners) {}

    /** A learner as people see it: what it is (descriptor) plus how it is governed right now (state and gate). */
    public record LearnerView(String learnerKey, String displayName, String summary, String loop, String requirement, List<String> outcomeDecisions,
                              List<String> liveParameters, AutonomyLevel autonomyLevel, boolean enabled, boolean paused, String pausedReason,
                              boolean running, String gateReason, int maxPromotionsPerWeek, Double maxStep, Instant lastRunAt, String updatedBy, Instant updatedAt) {}

    @GetMapping("/learners")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<LearnerView> learners() {
        Map<String, LearnerState> states = new java.util.HashMap<>();
        governance.states().forEach(s -> states.put(s.learnerKey(), s));
        return registry.descriptors().stream().map(d -> view(d, states.get(d.key()))).toList();
    }

    @GetMapping("/learners/{id}")
    @RequiresPermission(Permissions.LEARNING_READ)
    public LearnerView learner(@PathVariable("id") String learnerKey) {
        var d = descriptor(learnerKey);
        return view(d, learners.getOrCreate(learnerKey));
    }

    /** GV-01: changing how far a learner may act is a configuration change, so it also needs config.admin (§9.3). */
    @PutMapping("/learners/{id}/autonomy")
    @RequiresPermission(value = {Permissions.LEARNING_ADMIN, Permissions.CONFIG_ADMIN}, any = false)
    public LearnerView setAutonomy(@PathVariable("id") String learnerKey, @RequestBody AutonomyUpdate body) {
        if (body == null || body.autonomyLevel() == null) throw new ValidationException("autonomyLevel is required");
        var d = descriptor(learnerKey);
        return view(d, governance.setAutonomy(learnerKey, body.autonomyLevel(), actorRef()));
    }

    @PostMapping("/learners/{id}/resume")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public LearnerView resume(@PathVariable("id") String learnerKey) {
        var d = descriptor(learnerKey);
        governance.resume(learnerKey, actorRef());
        return view(d, learners.getOrCreate(learnerKey));
    }

    /** Runs a batch learner now for this tenant (still gated: a paused or switched-off learner does nothing). */
    @PostMapping("/learners/{id}/run")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public LearnerView run(@PathVariable("id") String learnerKey) {
        var d = descriptor(learnerKey);
        var learner = learnerBeans.stream().filter(l -> l.descriptor().key().equals(learnerKey)).findFirst().orElseThrow();
        if (!learner.runnable()) throw new ValidationException(d.displayName() + " learns from events as they happen and has no on-demand run");
        learner.runNow();
        return view(d, learners.getOrCreate(learnerKey));
    }

    /** Runs one of the learning system jobs now for this tenant: reward-maturity, coverage, auto-promote, guardrails. */
    @PostMapping("/jobs/{job}/run")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Map<String, Object> runJob(@PathVariable String job) {
        Object result = switch (job) {
            case "reward-maturity" -> rewardMaturity.sweepOnce();
            case "coverage" -> { coverage.sweepOnce(); yield coverage.latest(); }
            case "auto-promote" -> promotion.sweepOnce();
            case "guardrails" -> guardrails.sweepOnce();
            default -> throw new ValidationException("Unknown job '" + job + "' (reward-maturity, coverage, auto-promote, guardrails)");
        };
        return Map.of("job", job, "result", result);
    }

    @GetMapping("/kill-switch")
    @RequiresPermission(Permissions.LEARNING_READ)
    public KillSwitchState killSwitchState() {
        List<String> paused = governance.states().stream().filter(s -> !s.enabled() || s.paused()).map(LearnerState::learnerKey).toList();
        return new KillSwitchState(!governance.learningOn() && !tenantPaused(), tenantPaused(), paused);
    }

    @PostMapping("/kill-switch")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public KillSwitchState killSwitch(@RequestBody KillSwitch body) {
        if (body == null) throw new ValidationException("body is required");
        if (body.learnerKey() == null || body.learnerKey().isBlank() || "*".equals(body.learnerKey())) governance.setTenantKillSwitch(!body.enabled(), actorRef());
        else { descriptor(body.learnerKey()); governance.setEnabled(body.learnerKey(), body.enabled(), actorRef()); }
        return killSwitchState();
    }

    @GetMapping("/live-parameters")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<ai.intentoms.learning.governance.LiveParameterRepository.LiveParameter> liveParameters() { return liveParameters.all(); }

    @GetMapping("/coverage")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Double> coverage() { return coverage.latest(); }

    private boolean tenantPaused() { return config.getBoolean(ai.intentoms.learning.governance.LearnerGovernance.TENANT_KILL_SWITCH, false) || !config.getBoolean(ai.intentoms.learning.governance.LearnerGovernance.TENANT_ENABLED, true); }

    private ai.intentoms.learning.governance.LearnerDescriptor descriptor(String key) {
        return registry.descriptor(key).orElseThrow(() -> new ai.intentoms.platform.error.NotFoundException("No learner '" + key + "'"));
    }

    private LearnerView view(ai.intentoms.learning.governance.LearnerDescriptor d, LearnerState s) {
        LearnerState st = s == null ? LearnerState.defaultFor(d.key(), d.defaultAutonomy()) : s;
        var gate = governance.gate(d.key());
        return new LearnerView(d.key(), d.displayName(), d.summary(), d.loop().name(), d.requirement(), d.outcomeDecisions(), d.liveParameters(),
            st.autonomyLevel(), st.enabled(), st.paused(), st.pausedReason(), gate.mayRun(), gate.reason(), st.maxPromotionsPerWeek(), st.maxStep(),
            st.lastRunAt(), st.updatedBy(), st.updatedAt());
    }

    @GetMapping("/facts")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<LearnedFact> facts(@RequestParam("subjectType") SubjectType subjectType, @RequestParam("subjectId") String subjectId) {
        return facts.activeForSubject(subjectType, subjectId);
    }

    @GetMapping("/facts/{subjectType}/{subjectId}/{key}")
    @RequiresPermission(Permissions.LEARNING_READ)
    public LearnedFact fact(@PathVariable SubjectType subjectType, @PathVariable String subjectId, @PathVariable FactKey key) {
        return facts.active(subjectType, subjectId, key).orElseThrow(() -> new ai.intentoms.platform.error.NotFoundException("No active fact " + key + " for " + subjectType + ":" + subjectId));
    }

    @GetMapping("/proposals")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<Proposal> proposals(@RequestParam(value = "status", defaultValue = "DRAFT") String status) {
        return proposals.byStatus(status, 100);
    }

    @GetMapping("/proposals/{id}")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Proposal proposal(@PathVariable String id) { return proposals.get(id); }

    @PostMapping("/proposals/{id}/approve")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal approve(@PathVariable String id) {
        Proposal p = proposals.get(id);
        String by = actorRef();
        if (by.equals(p.createdBy())) throw new ValidationException("The author of a proposal cannot approve it themselves");
        proposals.approve(id, by, Instant.now());
        events.proposalApproved(id, ai.intentoms.platform.ledger.Actor.parse(by), Map.of("target", p.changeset().target().toString(), "tier", p.tier().name(), "author", p.createdBy()));
        return proposals.get(id);
    }

    @PostMapping("/proposals/{id}/reject")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        Proposal p = proposals.get(id);
        String reason = body == null ? null : body.get("reason");
        proposals.reject(id, reason, Instant.now());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("target", p.changeset().target().toString());
        payload.put("reason", reason);
        events.proposalRejected(id, ai.intentoms.platform.ledger.Actor.parse(actorRef()), payload);
        return proposals.get(id);
    }

    @GetMapping("/objective")
    @RequiresPermission(Permissions.LEARNING_READ)
    public ObjectiveProfile objective() {
        return objectives.live().orElseGet(() -> new ObjectiveProfile(0, ObjectiveProfile.Status.DRAFT, ObjectiveWeights.preset("balanced"), Map.of(), null, null, "system", Instant.now()));
    }

    /** XP-07: submitting weights creates a DRAFT; it goes live only through {@link #approveObjective} by another person. */
    @PutMapping("/objective")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public ObjectiveProfile setObjective(@RequestBody ObjectiveUpdate body) {
        if (body == null || body.weights() == null) throw new ValidationException("weights is required");
        return objectiveService.draft(body.weights(), body.constraints(), actorRef(), null, "manual", Map.of());
    }

    @GetMapping("/objective/drafts")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<ObjectiveProfile> objectiveDrafts() { return objectiveService.drafts(); }

    @GetMapping("/objective/history")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<ObjectiveProfile> objectiveHistory(@RequestParam(value = "limit", defaultValue = "20") int limit) { return objectiveService.history(Math.max(1, Math.min(limit, 100))); }

    @PostMapping("/objective/{version}/approve")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public ObjectiveProfile approveObjective(@PathVariable int version) { return objectiveService.approve(version, actorRef()); }

    private String actorRef() { return authz.requirePrincipal().actorRef(); }
}
