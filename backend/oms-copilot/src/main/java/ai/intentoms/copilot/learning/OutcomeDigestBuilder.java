package ai.intentoms.copilot.learning;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link OutcomeDigest} aggregates for the Analyst, grouped by a cohort key read from each episode's
 * decision input (node id, lane, segment, sku class — whichever the originating decision recorded), never by
 * order. A cohort with fewer than {@code minCellSize} mature episodes is dropped entirely rather than returned
 * small and noisy (SOO requirements doc §6.4, default 30).
 *
 * <p>Implementation note: this aggregates in the JVM over mature episodes rather than pushing the group-by into
 * SQL. {@code episode} doesn't carry its own scope dimensions (node/lane/segment) — those live on the originating
 * {@code decision_record.input} — so joining and grouping here avoids a schema change to a table other learners
 * also write to. Fine at today's volume; push to SQL if digest-building ever shows up in a profile.
 */
@Component
public class OutcomeDigestBuilder {
    private static final List<String> SCOPE_KEYS = List.of("nodeId", "node", "lane", "segment", "segmentId", "skuClass", "channel");
    private static final int DEFAULT_MIN_CELL_SIZE = 30;
    private static final int LOOKBACK_LIMIT = 5000;

    private final EpisodeRepository episodes;
    private final DecisionRecordRepository decisionRecords;

    public OutcomeDigestBuilder(EpisodeRepository episodes, DecisionRecordRepository decisionRecords) {
        this.episodes = episodes;
        this.decisionRecords = decisionRecords;
    }

    public List<OutcomeDigest> build(String decision, Instant since) { return build(decision, since, DEFAULT_MIN_CELL_SIZE); }

    public List<OutcomeDigest> build(String decision, Instant since, int minCellSize) {
        List<Episode> mature = episodes.forDecisionSince(decision, since, LOOKBACK_LIMIT).stream().filter(e -> e.status() == EpisodeStatus.MATURE && e.reward() != null).toList();
        Map<String, List<Episode>> byCohort = new LinkedHashMap<>();
        for (Episode e : mature) {
            DecisionRecord rec = decisionRecords.find(e.decisionId()).orElse(null);
            if (rec == null || rec.input() == null) continue;
            for (String key : SCOPE_KEYS) {
                Object v = rec.input().get(key);
                if (v == null) continue;
                byCohort.computeIfAbsent(key + "=" + v, k -> new ArrayList<>()).add(e);
            }
        }
        List<OutcomeDigest> out = new ArrayList<>();
        for (var entry : byCohort.entrySet()) {
            List<Episode> cohort = entry.getValue();
            if (cohort.size() < minCellSize) continue;
            int[] sep = {entry.getKey().indexOf('=')};
            String scopeKey = entry.getKey().substring(0, sep[0]);
            String scopeValue = entry.getKey().substring(sep[0] + 1);
            double meanReward = cohort.stream().mapToDouble(Episode::reward).average().orElse(0);
            Map<String, Double> meanComponents = new LinkedHashMap<>();
            for (Episode e : cohort) {
                if (e.rewardComponents() == null) continue;
                e.rewardComponents().forEach((k, v) -> meanComponents.merge(k, v, Double::sum));
            }
            meanComponents.replaceAll((k, v) -> v / cohort.size());
            out.add(new OutcomeDigest(decision, scopeKey, scopeValue, cohort.size(), meanReward, meanComponents));
        }
        return out;
    }
}
