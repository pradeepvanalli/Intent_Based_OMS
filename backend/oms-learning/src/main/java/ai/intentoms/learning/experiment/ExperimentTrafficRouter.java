package ai.intentoms.learning.experiment;

import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.hooks.ExperimentRouter;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.store.ExperimentRepository;
import ai.intentoms.learning.store.LearningBaselineRepository;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * EX-03 / EX-04 on the live path: which decision version a customer sees while experiments run. The persistent
 * holdout comes first, so a holdout customer is kept out of every promoted change and every canary: they get the
 * decision's pre-learning baseline version (or the live one when the decision was never changed, still tagged
 * {@code holdout} so lift can be measured). A canary customer gets the candidate version. Assignment is a sticky hash
 * of experiment and routing key (the {@code customerRef} the caller passes, else the subject), so the same customer
 * always lands in the same arm. The running experiments are cached for ten seconds; starting or stopping one in this
 * process clears the cache at once.
 */
@Component
public class ExperimentTrafficRouter implements ExperimentRouter {
    private final ExperimentRepository experiments;
    private final LearningBaselineRepository baselines;
    private final Cache<String, Snapshot> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(10_000).build();

    public ExperimentTrafficRouter(ExperimentRepository experiments, LearningBaselineRepository baselines) {
        this.experiments = experiments;
        this.baselines = baselines;
    }

    record Snapshot(Experiment holdout, Map<String, Experiment> canaryByDecision, Map<String, Integer> baselines) {}

    @Override
    public Optional<DecisionLearningHooks.Route> route(String decision, String routingKey, int resolvedVersion) {
        if (routingKey == null || routingKey.isBlank()) return Optional.empty();
        Snapshot s = cache.get(TenantContext.require(), k -> load());
        if (s.holdout() != null && ExperimentManager.stickyInTreatment(s.holdout().experimentId(), routingKey, s.holdout().trafficShare())) {
            return Optional.of(new DecisionLearningHooks.Route(s.holdout().experimentId(), "holdout", s.baselines().getOrDefault(decision, resolvedVersion)));
        }
        Experiment canary = s.canaryByDecision().get(decision);
        if (canary != null && canary.candidateVersion() != null && ExperimentManager.stickyInTreatment(canary.experimentId(), routingKey, canary.trafficShare())) {
            return Optional.of(new DecisionLearningHooks.Route(canary.experimentId(), "canary", canary.candidateVersion()));
        }
        return Optional.empty();
    }

    public void invalidate() {
        TenantContext.current().ifPresentOrElse(cache::invalidate, cache::invalidateAll);
    }

    private Snapshot load() {
        Experiment holdout = null;
        Map<String, Experiment> canaries = new HashMap<>();
        for (Experiment e : experiments.running()) {
            if (e.mode() == ExperimentMode.HOLDOUT) holdout = e;
            else if (e.mode() == ExperimentMode.CANARY && e.decisionName() != null) canaries.put(e.decisionName(), e);
        }
        return new Snapshot(holdout, canaries, holdout == null ? Map.of() : baselines.all());
    }
}
