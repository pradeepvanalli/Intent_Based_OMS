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

import java.util.Map;

/** EX-07 promotion and rollback, GV-06 crypto-shred propagation, GV-07 fairness parity. Same {@code /api/v1/learning}
 * base path as {@code LearningController}, different sub-paths. */
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

    /**
     * EX-07: a person takes an APPROVED change live. When its evidence or budget falls short the answer is 409 with code
     * {@code promotion_needs_override} and the list of checks in {@code waivable}; send {@code {"overrideReason": "..."}}
     * (at least ten characters) to promote anyway, which is recorded on the ledger and the change card.
     */
    @PostMapping("/proposals/{id}/promote")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal promote(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return promotion.promote(id, Actor.parse(authz.requirePrincipal().actorRef()), body == null ? null : body.get("overrideReason"));
    }

    /** AC4 / EX-05: revert a promoted change to exactly what it replaced. Body: {@code {"reason": "..."}} (optional). */
    @PostMapping("/promotions/{id}/rollback")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal rollback(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return promotion.rollback(id, Actor.parse(authz.requirePrincipal().actorRef()), body == null ? null : body.get("reason"));
    }

    @GetMapping("/fairness/{decision}")
    @RequiresPermission(Permissions.LEARNING_READ)
    public FairnessReportService.ParityReport fairness(@PathVariable String decision) { return fairness.report(decision, 90); }

    @PostMapping("/gdpr/purge-customer/{customerId}")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public void purgeCustomer(@PathVariable String customerId) { cryptoShred.purgeSubject(customerId); }
}
