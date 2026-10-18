package ai.intentoms.learning.api;

import ai.intentoms.learning.explain.ChangeCardService;
import ai.intentoms.learning.explain.CumulativeDiffService;
import ai.intentoms.learning.explain.LearnerMetricsService;
import ai.intentoms.learning.explain.LearningRetentionService;
import ai.intentoms.learning.explain.WeeklyLearningDigestService;
import ai.intentoms.learning.fairness.FairnessReportService;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Explainability, digest and non-functional endpoints of the self-optimizing layer: XP-02 change card and cumulative
 * diff, XP-04 weekly digests, GV-07 feature audit, NF-09 learner metrics and NF-10 retention. Same base path as
 * {@link LearningController}.
 */
@RestController
@RequestMapping("/api/v1/learning")
public class ExplainabilityController {
    private final ChangeCardService cards;
    private final CumulativeDiffService diffs;
    private final WeeklyLearningDigestService digests;
    private final FairnessReportService fairness;
    private final LearnerMetricsService learnerMetrics;
    private final LearningRetentionService retention;
    private final AuthorizationService authz;

    public ExplainabilityController(ChangeCardService cards, CumulativeDiffService diffs, WeeklyLearningDigestService digests, FairnessReportService fairness,
                                    LearnerMetricsService learnerMetrics, LearningRetentionService retention, AuthorizationService authz) {
        this.cards = cards;
        this.diffs = diffs;
        this.digests = digests;
        this.fairness = fairness;
        this.learnerMetrics = learnerMetrics;
        this.retention = retention;
        this.authz = authz;
    }

    /** XP-02: hypothesis, evidence, replay with confidence interval, critic review, diff, cumulative diff, allowed actions. */
    @GetMapping("/proposals/{id}/card")
    @RequiresPermission(Permissions.LEARNING_READ)
    public ChangeCardService.ChangeCard card(@PathVariable String id) { return cards.card(id, authz.requirePrincipal().actorRef()); }

    /** XP-02 / §11: everything promoted for a target since the last human-approved baseline. */
    @GetMapping("/diff")
    @RequiresPermission(Permissions.LEARNING_READ)
    public CumulativeDiffService.CumulativeDiff diff(@RequestParam("target") String target) {
        if (!target.contains(":")) throw new ValidationException("target must look like decision:<name> or parameter:<name>");
        return diffs.forTarget(target);
    }

    @GetMapping("/digests")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<WeeklyLearningDigestService.Digest> digests(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return digests.recent(Math.max(1, Math.min(limit, 52)));
    }

    @GetMapping("/digests/latest")
    @RequiresPermission(Permissions.LEARNING_READ)
    public WeeklyLearningDigestService.Digest latestDigest() { return digests.latest().orElseThrow(() -> new NotFoundException("No learning digest yet")); }

    /** XP-04: build, store and send the digest now (normally every Monday). */
    @PostMapping("/digests/run")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public WeeklyLearningDigestService.Digest runDigest(@RequestParam(value = "days", defaultValue = "7") int days) {
        return digests.publish(days, authz.requirePrincipal().actorRef());
    }

    /** GV-07: every learner's declared features checked against the protected-attribute list. */
    @GetMapping("/fairness/features")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<FairnessReportService.LearnerFeatureAudit> featureAudit() { return fairness.featureAudit("*"); }

    /** NF-09: update lag, coverage, proposal yield, promotion and rollback rate per learner. */
    @GetMapping("/metrics")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<LearnerMetricsService.LearnerMetrics> metrics() { return learnerMetrics.current(); }

    /** NF-10: run the retention sweep for this tenant now; dry run by default from the API. */
    @PostMapping("/retention/run")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Map<String, Object> retention(@RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return retention.run(dryRun, authz.requirePrincipal().actorRef());
    }
}
