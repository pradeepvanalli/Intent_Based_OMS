package ai.intentoms.learning.api;

import ai.intentoms.learning.fairness.FairnessReportService;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.promotion.CryptoShredPropagation;
import ai.intentoms.learning.promotion.PromotionService;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

/** EX-07 manual promotion, GV-06 crypto-shred propagation, GV-07 fairness parity. Separate controller from {@code
 * LearningController}/{@code EvaluationController}/{@code LearningLlmController} per this build's file-ownership
 * split — same {@code /api/v1/learning} base path, different sub-paths. */
@RestController
@RequestMapping("/api/v1/learning")
public class GovernanceController {
    private final PromotionService promotion;
    private final FairnessReportService fairness;
    private final CryptoShredPropagation cryptoShred;
    private final AuthorizationService authz;

    public GovernanceController(PromotionService promotion, FairnessReportService fairness, CryptoShredPropagation cryptoShred, AuthorizationService authz) {
        this.promotion = promotion;
        this.fairness = fairness;
        this.cryptoShred = cryptoShred;
        this.authz = authz;
    }

    @PostMapping("/proposals/{id}/promote")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal promote(@PathVariable String id) { return promotion.promote(id, Actor.parse(authz.requirePrincipal().actorRef())); }

    @GetMapping("/fairness/{decision}")
    @RequiresPermission(Permissions.LEARNING_READ)
    public FairnessReportService.ParityReport fairness(@PathVariable String decision) { return fairness.report(decision, 90); }

    @PostMapping("/gdpr/purge-customer/{customerId}")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public void purgeCustomer(@PathVariable String customerId) { cryptoShred.purgeSubject(customerId); }
}
