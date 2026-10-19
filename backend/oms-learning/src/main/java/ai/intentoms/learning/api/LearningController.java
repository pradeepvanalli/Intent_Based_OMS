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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ai.intentoms.learning.service.RewardBaselineService> rewardBaseline;
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
    /**
     * {@code learnerKey} empty or "*" means every learner for this tenant (GV-03 "pause all learning");
     * {@code scope: "global"} means every tenant (platform administrators only).
     */
    public record KillSwitch(String learnerKey, boolean enabled, String scope) {
        public KillSwitch(String learnerKey, boolean enabled) { this(learnerKey, enabled, null); }
    }
    /** {@code globalSource}: "property" (forced at startup), "runtime" (set through the API) or null when off. */
    public record KillSwitchState(boolean globalKillSwitch, String globalSource, String globalSetBy, Instant globalSetAt, boolean tenantPaused,
                                  List<String> pausedLearners, boolean canSetGlobal) {}
    /** GV-02: either field may be omitted to keep its current value; {@code clearMaxStep: true} removes the learner's own limit. */
    public record BudgetUpdate(Double maxStep, Integer maxPromotionsPerWeek, Boolean clearMaxStep) {}

    /** A learner as people see it: what it is (descriptor) plus how it is governed right now (state and gate). */
    public record LearnerView(String learnerKey, String displayName, String summary, String loop, String requirement, List<String> outcomeDecisions,
                              List<String> liveParameters, AutonomyLevel autonomyLevel, boolean enabled, boolean paused, String pausedReason,
                              boolean running, String gateReason, int maxPromotionsPerWeek, Double maxStep, Instant lastRunAt, String updatedBy, Instant updatedAt,
                              boolean runnable) {}

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

    /**
     * GV-02: a learner's change budget — the largest move one promotion of its proposals may make, and how many times
     * one target may be promoted per week. A configuration change, so it needs config.admin as well.
     */
    @PutMapping("/learners/{id}/budget")
    @RequiresPermission(value = {Permissions.LEARNING_ADMIN, Permissions.CONFIG_ADMIN}, any = false)
    public LearnerView setBudget(@PathVariable("id") String learnerKey, @RequestBody BudgetUpdate body) {
        if (body == null) throw new ValidationException("body is required");
        var d = descriptor(learnerKey);
        LearnerState current = learners.getOrCreate(learnerKey);
        Double maxStep = Boolean.TRUE.equals(body.clearMaxStep()) ? null : body.maxStep() != null ? body.maxStep() : current.maxStep();
        return view(d, governance.setBudget(learnerKey, maxStep, body.maxPromotionsPerWeek(), actorRef()));
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
            case "reward-baseline" -> rewardBaseline.getObject().recompute();
            default -> throw new ValidationException("Unknown job '" + job + "' (reward-maturity, coverage, auto-promote, guardrails, reward-baseline)");
        };
        return Map.of("job", job, "result", result);
    }

    @GetMapping("/kill-switch")
    @RequiresPermission(Permissions.LEARNING_READ)
    public KillSwitchState killSwitchState() {
        List<String> paused = governance.states().stream().filter(s -> !s.enabled() || s.paused()).map(LearnerState::learnerKey).toList();
        var global = governance.globalState();
        return new KillSwitchState(global.on(), global.source(), global.updatedBy(), global.updatedAt(), tenantPaused(), paused, platformAdmin());
    }

    /**
     * GV-03 at three levels: one learner ({@code learnerKey}), every learner of this tenant ({@code learnerKey: "*"}),
     * or every tenant ({@code scope: "global"}, platform engineers only). {@code enabled: false} switches off.
     */
    @PostMapping("/kill-switch")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public KillSwitchState killSwitch(@RequestBody KillSwitch body) {
        if (body == null) throw new ValidationException("body is required");
        if ("global".equalsIgnoreCase(body.scope())) {
            if (!platformAdmin()) throw new ai.intentoms.platform.error.ForbiddenException("Only a platform engineer can switch learning off or on for every tenant; a tenant administrator can pause this tenant's learning instead");
            governance.setGlobalKillSwitch(!body.enabled(), actorRef());
        } else if (body.learnerKey() == null || body.learnerKey().isBlank() || "*".equals(body.learnerKey())) {
            governance.setTenantKillSwitch(!body.enabled(), actorRef());
        } else {
            descriptor(body.learnerKey());
            governance.setEnabled(body.learnerKey(), body.enabled(), actorRef());
        }
        return killSwitchState();
    }

    /** The role that may switch learning off or on for every tenant. */
    public static final String PLATFORM_ROLE = "platform_engineer";

    /**
     * N3: a platform engineer is known by role, not by holding every permission: the built-in tenant {@code admin}
     * role holds "*" too, and one tenant's administrator must not stop learning for all the others.
     */
    private boolean platformAdmin() {
        return authz.currentPrincipal().map(p -> p.roles() != null && p.roles().contains(PLATFORM_ROLE)).orElse(false);
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

    private boolean runnable(String key) {
        return learnerBeans.stream().anyMatch(l -> l.descriptor().key().equals(key) && l.runnable());
    }

    private LearnerView view(ai.intentoms.learning.governance.LearnerDescriptor d, LearnerState s) {
        LearnerState st = s == null ? LearnerState.defaultFor(d.key(), d.defaultAutonomy()) : s;
        var gate = governance.gate(d.key());
        return new LearnerView(d.key(), d.displayName(), d.summary(), d.loop().name(), d.requirement(), d.outcomeDecisions(), d.liveParameters(),
            st.autonomyLevel(), st.enabled(), st.paused(), st.pausedReason(), gate.mayRun(), gate.reason(), st.maxPromotionsPerWeek(), st.maxStep(),
            st.lastRunAt(), st.updatedBy(), st.updatedAt(), runnable(d.key()));
    }

    /** §9.3: {@code ?subject=node:DC-TX} (as documented) or {@code ?subjectType=NODE&subjectId=DC-TX}. */
    @GetMapping("/facts")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<LearnedFact> facts(@RequestParam(value = "subjectType", required = false) String subjectType, @RequestParam(value = "subjectId", required = false) String subjectId,
                                   @RequestParam(value = "subject", required = false) String subject) {
        String type = subjectType, id = subjectId;
        if (subject != null && !subject.isBlank()) {
            int colon = subject.indexOf(':');
            if (colon <= 0 || colon == subject.length() - 1) throw new ValidationException("subject must look like <type>:<id>, e.g. node:DC-TX");
            type = subject.substring(0, colon);
            id = subject.substring(colon + 1);
        }
        if (type == null || type.isBlank() || id == null || id.isBlank()) throw new ValidationException("Give subject=<type>:<id>, or subjectType and subjectId");
        SubjectType st;
        try { st = SubjectType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ValidationException("Unknown subject type '" + type + "'; one of " + java.util.Arrays.toString(SubjectType.values())); }
        return facts.activeForSubject(st, id);
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
    public Proposal approve(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        Proposal p = proposals.get(id);
        String by = actorRef();
        // The author never approves, whatever the status, so that refusal comes first (422); then the lifecycle (409).
        if (by.equals(p.createdBy())) throw new ValidationException("The author of a proposal cannot approve it themselves");
        ai.intentoms.learning.model.ProposalLifecycle.require(p, ai.intentoms.learning.model.ProposalStatus.APPROVED, "approve");
        String reasonCode = body == null ? null : body.get("reasonCode");
        if (ai.intentoms.learning.promotion.PromotionGuard.isPostalCodeScoped(p.changeset())) {
            // GV-07: a restriction scoped to postal codes can disadvantage a neighbourhood; it needs a stated reason.
            List<String> allowed = config.getList("learning.postal_reason_codes").stream().map(String::valueOf).toList();
            if (allowed.isEmpty()) allowed = POSTAL_REASON_CODES;
            if (reasonCode == null || !allowed.contains(reasonCode)) {
                throw new ValidationException("This change is scoped to postal codes, so approving it needs a reason code: one of " + allowed);
            }
        }
        proposals.approve(id, by, Instant.now());
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of("target", p.changeset().target().toString(), "tier", p.tier().name(), "author", p.createdBy()));
        if (reasonCode != null) payload.put("reasonCode", reasonCode);
        events.proposalApproved(id, ai.intentoms.platform.ledger.Actor.parse(by), payload);
        return proposals.get(id);
    }

    static final List<String> POSTAL_REASON_CODES = List.of("carrier_service_gap", "confirmed_fraud_ring", "regulatory_restriction", "natural_disaster");

    @PostMapping("/proposals/{id}/reject")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        Proposal p = proposals.get(id);
        ai.intentoms.learning.model.ProposalLifecycle.require(p, ai.intentoms.learning.model.ProposalStatus.REJECTED, "reject");
        String reason = body == null ? null : body.get("reason");
        proposals.reject(id, reason, Instant.now());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("target", p.changeset().target().toString());
        payload.put("reason", reason);
        var actor = ai.intentoms.platform.ledger.Actor.parse(actorRef());
        events.proposalRejected(id, actor, payload);
        // §5: a rejected change returns to its hypothesis as evidence against it.
        promotion.counterEvidence(p, "Rejected " + Instant.now().toString().substring(0, 10) + (reason == null || reason.isBlank() ? "" : ": " + reason), actor);
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
