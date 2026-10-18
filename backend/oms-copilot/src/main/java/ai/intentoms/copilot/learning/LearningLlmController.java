package ai.intentoms.copilot.learning;

import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.Review;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manual triggers and audit views for the Analyst/Author/Critic roles (SOO requirements doc §6), on their own base path
 * next to {@code LearningController}. The scheduled runs cover normal operation; these exist for ops, testing and for
 * driving one hypothesis through the pipeline on demand.
 */
@RestController
@RequestMapping("/api/v1/learning/llm")
public class LearningLlmController {
    private final AnalystService analyst;
    private final AuthorService author;
    private final CriticService critic;
    private final AuthorScheduler authorScheduler;
    private final LearningLlmAudit audit;

    public LearningLlmController(AnalystService analyst, AuthorService author, CriticService critic, AuthorScheduler authorScheduler, LearningLlmAudit audit) {
        this.analyst = analyst;
        this.author = author;
        this.critic = critic;
        this.authorScheduler = authorScheduler;
        this.audit = audit;
    }

    @PostMapping("/analyst/run")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public List<Hypothesis> runAnalyst(@RequestParam(value = "decision", required = false) String decision,
                                        @RequestParam(value = "lookbackDays", defaultValue = "90") int lookbackDays) {
        Instant since = Instant.now().minus(Duration.ofDays(lookbackDays));
        return decision == null || decision.isBlank() ? analyst.runOnce() : analyst.analyze(decision, since);
    }

    @PostMapping("/author/{hypothesisId}")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Proposal runAuthor(@PathVariable String hypothesisId) {
        return author.draft(hypothesisId).orElseThrow(() -> new NotFoundException("Author produced no proposal for hypothesis " + hypothesisId
            + " (not open, no model key, budget reached, or output did not validate; see GET /api/v1/learning/llm/calls)"));
    }

    /** DF-04: the nightly auto-draft pass, now. */
    @PostMapping("/author/run")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public List<Proposal> runAuthorPass() { return authorScheduler.runOnce(); }

    @PostMapping("/critic/{proposalId}")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Review runCritic(@PathVariable String proposalId) {
        Optional<Review> r = critic.review(proposalId);
        return r.orElseThrow(() -> new NotFoundException("Critic produced no review for proposal " + proposalId + " (no evaluation report yet, no model key, budget reached, or output did not validate)"));
    }

    /** §6.3: recent analyst/author/critic calls with the schema and prompt hashes they ran under. */
    @GetMapping("/calls")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<Map<String, Object>> calls(@RequestParam(value = "limit", defaultValue = "50") int limit) { return audit.recentCalls(Math.min(500, Math.max(1, limit))); }

    /** §6.3: share of author drafts the critic called safe and replay found better. */
    @GetMapping("/author-quality")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> authorQuality(@RequestParam(value = "days", defaultValue = "30") int days) { return audit.authorQuality(days); }

    /** NF-03: this month's self-optimizing LLM spend against the tenant's budget. */
    @GetMapping("/budget")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> budget() { return audit.budgetStatus(); }
}
