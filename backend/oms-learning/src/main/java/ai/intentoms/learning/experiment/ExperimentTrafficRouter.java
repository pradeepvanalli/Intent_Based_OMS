package ai.intentoms.learning.experiment;

import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.governance.HoldoutCheck;
import ai.intentoms.learning.hooks.ExperimentRouter;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.store.ExperimentRepository;
import ai.intentoms.learning.store.LearningBaselineRepository;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * EX-03 / EX-04 on the live path: which decision version a customer sees while experiments run, and whether learned
 * values reach them at all. The persistent holdout comes first: a holdout customer gets each decision's pre-learning
 * baseline version (or the live one when the decision was never changed, still tagged {@code holdout} so lift can be
 * measured), and — through {@link HoldoutCheck} — the static value of every learned parameter, so lift measures
 * everything learning changed, not only decision versions. A canary customer gets the candidate version.
 *
 * <p>Assignment is per customer: the routing key is {@code customer:<ref>} when the caller passes {@code customerRef},
 * otherwise the subject is resolved to its customer ({@link CustomerKeyResolver}, e.g. an order to the customer who
 * placed it), and only when no customer can be found does a subject route on its own id. The same customer therefore
 * lands in the same arm for every decision and every order. Running experiments and resolved customers are cached
 * briefly; starting or stopping an experiment in this process clears the experiment cache at once.
 */
@Component
public class ExperimentTrafficRouter implements ExperimentRouter, HoldoutCheck {
    private final ExperimentRepository experiments;
    private final LearningBaselineRepository baselines;
    private final ObjectProvider<CustomerKeyResolver> resolvers;
    private final Cache<String, Snapshot> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(10_000).build();
    private final Cache<String, String> customers = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(200_000).build();

    public ExperimentTrafficRouter(ExperimentRepository experiments, LearningBaselineRepository baselines, ObjectProvider<CustomerKeyResolver> resolvers) {
        this.experiments = experiments;
        this.baselines = baselines;
        this.resolvers = resolvers;
    }

    record Snapshot(Experiment holdout, Map<String, Experiment> canaryByDecision, Map<String, Integer> baselines) {}

    @Override
    public Optional<DecisionLearningHooks.Route> route(String decision, String routingKey, int resolvedVersion) {
        String key = customerKey(routingKey);
        if (key == null) return Optional.empty();
        Snapshot s = snapshot();
        if (s.holdout() != null && ExperimentManager.stickyInTreatment(s.holdout().experimentId(), key, s.holdout().trafficShare())) {
            return Optional.of(new DecisionLearningHooks.Route(s.holdout().experimentId(), "holdout", s.baselines().getOrDefault(decision, resolvedVersion)));
        }
        Experiment canary = s.canaryByDecision().get(decision);
        if (canary != null && canary.candidateVersion() != null && ExperimentManager.stickyInTreatment(canary.experimentId(), key, canary.trafficShare())) {
            return Optional.of(new DecisionLearningHooks.Route(canary.experimentId(), "canary", canary.candidateVersion()));
        }
        return Optional.empty();
    }

    @Override
    public boolean inHoldout(String routingKey) {
        String key = customerKey(routingKey);
        if (key == null) return false;
        Experiment holdout = snapshot().holdout();
        return holdout != null && ExperimentManager.stickyInTreatment(holdout.experimentId(), key, holdout.trafficShare());
    }

    /**
     * The key experiments split on: {@code customer:<ref>} whenever a customer is known, else the routing key as given.
     * Accepts {@code customer:<ref>}, {@code <subjectType>:<subjectId>} or a bare subject id.
     */
    public String customerKey(String routingKey) {
        if (routingKey == null || routingKey.isBlank()) return null;
        if (routingKey.startsWith("customer:")) return routingKey;
        int colon = routingKey.indexOf(':');
        if (colon <= 0) return routingKey;
        String type = routingKey.substring(0, colon), id = routingKey.substring(colon + 1);
        String cacheKey = TenantContext.require() + "|" + routingKey;
        String resolved = customers.get(cacheKey, k -> resolvers.orderedStream().map(r -> r.customerOf(type, id)).flatMap(Optional::stream)
            .findFirst().map(c -> "customer:" + c).orElse(routingKey));
        return resolved;
    }

    public void invalidate() {
        TenantContext.current().ifPresentOrElse(cache::invalidate, cache::invalidateAll);
    }

    private Snapshot snapshot() { return cache.get(TenantContext.require(), k -> load()); }

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
