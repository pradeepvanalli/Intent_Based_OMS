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
import java.util.Optional;

/**
 * Manual triggers for the Analyst/Author/Critic roles (SOO requirements doc §6), separate base path from {@code
 * LearningController} to avoid touching that file. The scheduled analyst run covers normal operation; these exist
 * for ops/testing and for driving one hypothesis through the pipeline on demand.
 */
@RestController
@RequestMapping("/api/v1/learning/llm")
public class LearningLlmController {
    private final AnalystService analyst;
    private final AuthorService author;
    private final CriticService critic;

    public LearningLlmController(AnalystService analyst, AuthorService author, CriticService critic) {
        this.analyst = analyst;
        this.author = author;
        this.critic = critic;
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
            + " (not open, no model key, or output did not validate — check logs)"));
    }

    @PostMapping("/critic/{proposalId}")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Review runCritic(@PathVariable String proposalId) {
        Optional<Review> r = critic.review(proposalId);
        return r.orElseThrow(() -> new NotFoundException("Critic produced no review for proposal " + proposalId + " (no evaluation report yet, no model key, or output did not validate)"));
    }
}
