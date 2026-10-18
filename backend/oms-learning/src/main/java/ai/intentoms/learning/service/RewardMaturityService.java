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
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * OC-04: moves episodes OPEN -&gt; PROVISIONAL as soon as a reward can be estimated, then re-corrects and moves them
 * PROVISIONAL -&gt; MATURE once the longest outcome-maturity window has elapsed (defaults: delivery 14d, return 45d,
 * chargeback 120d — this service runs maturity at the longest of the three, 120 days, since any outcome up to then
 * can still correct the reward; see {@link RewardCalculator} for exactly which terms are currently correctable
 * from ledger data alone).
 */
@Component
public class RewardMaturityService {
    private static final Logger log = LoggerFactory.getLogger(RewardMaturityService.class);

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final ObjectiveProfileRepository objectives;
    private final DecisionRecordRepository decisionRecords;
    private final LearningEvents events;
    private final Metrics metrics;

    @Value("${oms.learning.maturity-window-days:120}") private int maturityWindowDays;

    public RewardMaturityService(EpisodeRepository episodes, OutcomeRepository outcomes, ObjectiveProfileRepository objectives,
                                  DecisionRecordRepository decisionRecords, LearningEvents events, Metrics metrics) {
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.objectives = objectives;
        this.decisionRecords = decisionRecords;
        this.events = events;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${oms.learning.provisional-sweep-ms:300000}", initialDelayString = "${oms.learning.provisional-sweep-ms:300000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Reward maturity sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int processed = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(maturityWindowDays + 1))));
        for (String tenant : tenants) {
            processed += TenantContext.supplyAs(tenant, this::sweepTenant);
        }
        return processed;
    }

    private int sweepTenant() {
        ObjectiveWeights weights = objectives.live().map(ObjectiveProfile::weights).orElseGet(() -> ObjectiveWeights.preset("balanced"));
        List<Episode> candidates = episodes.openOrProvisional(500);
        Instant now = Instant.now();
        int n = 0;
        for (Episode ep : candidates) {
            List<OutcomeRecord> outs = outcomes.forEpisode(ep.episodeId());
            boolean matureNow = ep.createdAt().plus(Duration.ofDays(maturityWindowDays)).isBefore(now);
            if (outs.isEmpty() && !matureNow) continue;
            Map<String, Double> components = RewardCalculator.components(outs);
            double reward = RewardCalculator.compute(components, weights);
            EpisodeStatus status = matureNow ? EpisodeStatus.MATURE : EpisodeStatus.PROVISIONAL;
            Instant maturedAt = matureNow ? now : null;
            episodes.updateStatusAndReward(ep.episodeId(), status, reward, components, maturedAt);
            metrics.count("oms.learning.episode.status_changed", "decision", ep.decisionName(), "to", status.name());
            if (matureNow) {
                events.episodeMatured(ep.episodeId(), Actor.system("reward-maturity"),
                    Map.of("decision", ep.decisionName(), "orderId", String.valueOf(ep.orderId()), "reward", reward, "components", components));
                metrics.count("oms.learning.reward", reward, "decision", ep.decisionName());
            }
            n++;
        }
        metrics.count("oms.learning.reward_maturity.episodes_processed", n, "tenant", TenantContext.require());
        return n;
    }
}
