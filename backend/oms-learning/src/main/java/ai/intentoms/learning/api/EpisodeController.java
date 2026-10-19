package ai.intentoms.learning.api;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OC-02 / OC-03, read only: the episodes opened for an order (one per decision point) and the typed outcomes attached
 * to each, so a person or a test can see an outcome land on the decision it judges.
 */
@RestController
@RequestMapping("/api/v1/learning/episodes")
public class EpisodeController {
    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;

    public EpisodeController(EpisodeRepository episodes, OutcomeRepository outcomes) {
        this.episodes = episodes;
        this.outcomes = outcomes;
    }

    public record EpisodeView(Episode episode, List<OutcomeRecord> outcomes) {}

    @GetMapping
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<EpisodeView> forOrder(@RequestParam(value = "orderId", required = false) String orderId) {
        if (orderId == null || orderId.isBlank()) throw new ValidationException("orderId is required");
        return episodes.forOrder(orderId).stream().map(e -> new EpisodeView(e, outcomes.forEpisode(e.episodeId()))).toList();
    }
}
