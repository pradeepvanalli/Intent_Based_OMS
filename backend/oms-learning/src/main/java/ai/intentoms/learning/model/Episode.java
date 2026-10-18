package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/**
 * One decision point plus everything that followed it, joined into one record (requirements doc §4). Assembled by
 * {@code EpisodeAssembler} from a {@code decision_record} row and the ledger events that followed it.
 */
public record Episode(String episodeId, String decisionId, String decisionName, String orderId, String lineId, String fulfillmentGroupId,
                       EpisodeStatus status, Double reward, Map<String, Double> rewardComponents, Integer policyVersion, Instant createdAt, Instant maturedAt) {
    public Episode withStatus(EpisodeStatus s, Instant maturedAt) {
        return new Episode(episodeId, decisionId, decisionName, orderId, lineId, fulfillmentGroupId, s, reward, rewardComponents, policyVersion, createdAt, maturedAt);
    }
    public Episode withReward(double reward, Map<String, Double> components) {
        return new Episode(episodeId, decisionId, decisionName, orderId, lineId, fulfillmentGroupId, status, reward, components, policyVersion, createdAt, maturedAt);
    }
}
