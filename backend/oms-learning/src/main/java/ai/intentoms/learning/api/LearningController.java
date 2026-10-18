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

    public LearningController(LearnerRepository learners, LearnedFactRepository facts, ProposalRepository proposals, ObjectiveProfileRepository objectives, AuthorizationService authz) {
        this.learners = learners;
        this.facts = facts;
        this.proposals = proposals;
        this.objectives = objectives;
        this.authz = authz;
    }

    public record AutonomyUpdate(AutonomyLevel autonomyLevel) {}
    public record ObjectiveUpdate(ObjectiveWeights weights, Map<String, Object> constraints) {}
    public record KillSwitch(String learnerKey, boolean enabled) {}

    @GetMapping("/learners")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<LearnerState> learners() { return learners.all(); }

    @PutMapping("/learners/{id}/autonomy")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public LearnerState setAutonomy(@PathVariable("id") String learnerKey, @RequestBody AutonomyUpdate body) {
        if (body == null || body.autonomyLevel() == null) throw new ValidationException("autonomyLevel is required");
        learners.setAutonomy(learnerKey, body.autonomyLevel(), actorRef(), Instant.now());
        return learners.getOrCreate(learnerKey);
    }

    @PostMapping("/kill-switch")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public LearnerState killSwitch(@RequestBody KillSwitch body) {
        if (body == null || body.learnerKey() == null || body.learnerKey().isBlank()) throw new ValidationException("learnerKey is required");
        learners.killSwitch(body.learnerKey(), body.enabled(), actorRef(), Instant.now());
        return learners.getOrCreate(body.learnerKey());
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
        return proposals.get(id);
    }

    @PostMapping("/proposals/{id}/reject")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        proposals.reject(id, body == null ? null : body.get("reason"), Instant.now());
        return proposals.get(id);
    }

    @GetMapping("/objective")
    @RequiresPermission(Permissions.LEARNING_READ)
    public ObjectiveProfile objective() {
        return objectives.live().orElseGet(() -> new ObjectiveProfile(0, ObjectiveProfile.Status.DRAFT, ObjectiveWeights.preset("balanced"), Map.of(), null, null, "system", Instant.now()));
    }

    @PutMapping("/objective")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public ObjectiveProfile setObjective(@RequestBody ObjectiveUpdate body) {
        if (body == null || body.weights() == null) throw new ValidationException("weights is required");
        int v = objectives.nextVersion();
        String by = actorRef();
        Instant now = Instant.now();
        ObjectiveProfile p = new ObjectiveProfile(v, ObjectiveProfile.Status.APPROVED, body.weights(), body.constraints() == null ? Map.of() : body.constraints(), by, now, by, now);
        objectives.insert(p);
        objectives.approve(v, by, now);
        return p;
    }

    private String actorRef() { return authz.requirePrincipal().actorRef(); }
}
