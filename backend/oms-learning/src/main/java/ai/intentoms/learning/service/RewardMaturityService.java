package ai.intentoms.learning.service;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OC-04: rewards are provisional first and corrected as outcomes mature. An episode moves OPEN -&gt; PROVISIONAL as
 * soon as its first outcome lands, is recomputed whenever another outcome arrives, and moves to MATURE once every
 * reward term that carries weight in the live objective is mature: a delivery-driven term (on time, fill, cost,
 * split, aged stock) is mature as soon as its outcome arrives or the delivery window closes; contacts and fraud loss
 * only when their own window closes, since a complaint or chargeback can still come. Windows come from
 * {@link MaturityWindows} (tenant config). The sweep only touches episodes with something new to do, newest outcome
 * work first, so a large backlog of idle episodes never starves recent ones. An episode that matures with no outcome
 * at all gets a null reward (and so counts against OC-07 coverage) instead of a made-up zero.
 */
@Component
public class RewardMaturityService {
    private static final Logger log = LoggerFactory.getLogger(RewardMaturityService.class);
    private static final Set<String> CLOSE_ON_EVIDENCE = Set.of("otd", "fill", "cost", "split", "inv");
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 20;

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final ObjectiveProfileRepository objectives;
    private final DecisionRecordRepository decisionRecords;
    private final TenantConfigStore config;
    private final LearningEvents events;
    private final Metrics metrics;

    public RewardMaturityService(EpisodeRepository episodes, OutcomeRepository outcomes, ObjectiveProfileRepository objectives,
                                  DecisionRecordRepository decisionRecords, TenantConfigStore config, LearningEvents events, Metrics metrics) {
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.objectives = objectives;
        this.decisionRecords = decisionRecords;
        this.config = config;
        this.events = events;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${oms.learning.provisional-sweep-ms:300000}", initialDelayString = "${oms.learning.provisional-sweep-ms:300000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Reward maturity sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int processed = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(400))));
        for (String tenant : tenants) processed += TenantContext.supplyAs(tenant, this::sweepTenant);
        return processed;
    }

    private int sweepTenant() {
        ObjectiveWeights weights = objectives.live().map(ObjectiveProfile::weights).orElseGet(() -> ObjectiveWeights.preset("balanced"));
        MaturityWindows windows = MaturityWindows.forTenant(config);
        Instant now = Instant.now();
        Duration fullyMature = RewardCalculator.TERMS.stream().filter(t -> RewardCalculator.weight(weights, t) != 0)
            .map(windows::forTerm).max(Duration::compareTo).orElse(windows.longest());
        int n = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Episode> due = episodes.dueForReward(now.minus(fullyMature), BATCH);
            for (Episode ep : due) { process(ep, weights, windows, now); n++; }
            if (due.size() < BATCH) break;
        }
        metrics.count("oms.learning.reward_maturity.episodes_processed", n, "tenant", TenantContext.require());
        return n;
    }

    private Map<String, Double> baselineScales() {
        RewardBaselineService b = baseline == null ? null : baseline.getIfAvailable();
        return b == null ? Map.of() : b.current();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<RewardBaselineService> baseline;

    void process(Episode ep, ObjectiveWeights weights, MaturityWindows windows, Instant now) {
        List<OutcomeRecord> outs = outcomes.forEpisode(ep.episodeId());
        Set<String> scored = RewardCalculator.scoredTerms(outs);
        boolean mature = true;
        double orderValue = RewardCalculator.needsOrderValue(outs) ? outcomes.orderValueForEpisode(ep.episodeId(), ep.orderId()).orElse(0.0) : 0.0;
        Map<String, Double> components = new LinkedHashMap<>(RewardCalculator.normalise(RewardCalculator.components(outs, orderValue), baselineScales()));
        for (String term : RewardCalculator.TERMS) {
            if (RewardCalculator.weight(weights, term) == 0) continue;
            boolean windowClosed = !ep.createdAt().plus(windows.forTerm(term)).isAfter(now);
            if (!windowClosed && !(CLOSE_ON_EVIDENCE.contains(term) && scored.contains(term))) mature = false;
        }
        Double reward = outs.isEmpty() ? null : RewardCalculator.compute(components, weights);
        components.putAll(OutcomeAttribution.attribute(outs)); // OC-05: who most likely caused a bad outcome, stored with the reward
        EpisodeStatus status = mature ? EpisodeStatus.MATURE : outs.isEmpty() ? EpisodeStatus.OPEN : EpisodeStatus.PROVISIONAL;
        episodes.updateStatusAndReward(ep.episodeId(), status, reward, components, mature ? now : null);
        if (status != ep.status()) metrics.count("oms.learning.episode.status_changed", "decision", ep.decisionName(), "to", status.name());
        if (mature) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decision", ep.decisionName());
            payload.put("orderId", String.valueOf(ep.orderId()));
            payload.put("reward", reward);
            payload.put("components", components);
            payload.put("scoredTerms", List.copyOf(scored));
            String blamed = OutcomeAttribution.primary(components);
            if (blamed != null) payload.put("attributedTo", blamed);
            events.episodeMatured(ep.episodeId(), Actor.system("reward-maturity"), payload);
            if (reward != null) metrics.count("oms.learning.reward", reward, "decision", ep.decisionName());
        }
    }
}
