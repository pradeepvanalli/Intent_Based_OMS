package ai.intentoms.learning.api;

import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.governance.LiveParameterRepository;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning a learned value on for live decisions is itself a proposal (EX-07): this drafts a {@code ParameterChange}
 * against {@code parameter:live:<family>} with the allowed range and max step, which then goes through evaluate,
 * approval by a different person, and promotion like any other change. Turning it off is a safety action, so
 * {@code revoke} takes effect at once and live paths fall back to their static values.
 */
@RestController
@RequestMapping("/api/v1/learning/live-parameters")
public class LiveUseController {
    private final ProposalRepository proposals;
    private final LearnerRegistry registry;
    private final LiveParameterRepository live;
    private final ParameterResolver resolver;
    private final AuthorizationService authz;
    private final ai.intentoms.learning.promotion.PromotionService promotion;

    public LiveUseController(ProposalRepository proposals, LearnerRegistry registry, LiveParameterRepository live, ParameterResolver resolver, AuthorizationService authz,
                             ai.intentoms.learning.promotion.PromotionService promotion) {
        this.promotion = promotion;
        this.proposals = proposals;
        this.registry = registry;
        this.live = live;
        this.resolver = resolver;
        this.authz = authz;
    }

    /** {@code enabled} defaults to true; false drafts a proposal to switch live use off through the normal gate. */
    public record LiveUseRequest(String family, String learnerKey, Double min, Double max, Double maxStep, Boolean enabled, String reason) {}

    @PostMapping("/proposals")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal propose(@RequestBody LiveUseRequest r) {
        if (r == null || r.family() == null || r.family().isBlank()) throw new ValidationException("family is required");
        if (r.learnerKey() == null || r.learnerKey().isBlank()) throw new ValidationException("learnerKey is required");
        LearnerDescriptor d = registry.descriptor(r.learnerKey()).orElseThrow(() -> new NotFoundException("No learner '" + r.learnerKey() + "'"));
        if (!d.liveParameters().isEmpty() && !d.liveParameters().contains(r.family())) {
            throw new ValidationException(d.displayName() + " does not feed '" + r.family() + "'; it feeds " + d.liveParameters());
        }
        if (r.min() != null && r.max() != null && r.min() > r.max()) throw new ValidationException("min must not exceed max");
        if (r.maxStep() != null && r.maxStep() <= 0) throw new ValidationException("maxStep must be positive");
        boolean enable = r.enabled() == null || r.enabled();

        Map<String, Object> from = new LinkedHashMap<>();
        live.find(r.family()).ifPresentOrElse(cur -> {
            from.put("enabled", cur.live());
            if (cur.min() != null) from.put("min", cur.min());
            if (cur.max() != null) from.put("max", cur.max());
            if (cur.maxStep() != null) from.put("maxStep", cur.maxStep());
        }, () -> from.put("enabled", false));
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("enabled", enable);
        to.put("learner", r.learnerKey());
        if (r.min() != null) to.put("min", r.min());
        if (r.max() != null) to.put("max", r.max());
        if (r.maxStep() != null) to.put("maxStep", r.maxStep());

        String reason = r.reason() == null || r.reason().isBlank()
            ? (enable ? "Use " + d.displayName() + "'s learned value for " + r.family() + " in live decisions" : "Stop using " + r.family() + " in live decisions")
            : r.reason();
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("parameter", "live:" + r.family()), 0,
            List.of(new PolicyChange.ParameterChange("live:" + r.family(), from, to)), reason, PolicyChangeset.RiskTier.T1);
        Instant now = Instant.now();
        Proposal p = new Proposal(Ids.prefixed("prop"), cs, null, PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null,
            authz.requirePrincipal().actorRef(), now, now, null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return proposals.get(p.proposalId());
    }

    /** Switches a family off at once; the proposal that switched it on is closed as rolled back (defect 27). */
    @PostMapping("/{family}/revoke")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Map<String, Object> revoke(@PathVariable String family) {
        String by = authz.requirePrincipal().actorRef();
        boolean changed = resolver.revoke(family, by);
        if (changed) promotion.liveUseRevoked(family, ai.intentoms.platform.ledger.Actor.parse(by));
        return Map.of("family", family, "revoked", changed);
    }
}
