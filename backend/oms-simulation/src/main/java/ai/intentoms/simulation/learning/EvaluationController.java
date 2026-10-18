package ai.intentoms.simulation.learning;

import ai.intentoms.learning.model.Proposal;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** EX-01: run replay evaluation now. A separate controller from {@code LearningController} (different module,
 * same {@code /api/v1/learning} base path) so the evaluator's build stays independent of the core SOO CRUD surface. */
@RestController
@RequestMapping("/api/v1/learning/proposals")
public class EvaluationController {
    private final ProposalEvaluationService evaluations;
    private final AuthorizationService authz;

    public EvaluationController(ProposalEvaluationService evaluations, AuthorizationService authz) {
        this.evaluations = evaluations;
        this.authz = authz;
    }

    @PostMapping("/{id}/evaluate")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal evaluate(@PathVariable String id) {
        return evaluations.evaluate(id, authz.requirePrincipal().actorRef());
    }
}
