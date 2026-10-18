package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/**
 * One decision point plus everything that followed it, joined into one record (requirements doc §4). Assembled by
 * {@code EpisodeAssembler} from the first {@code decision_record} of the point (the anchor, {@code decisionId}); later
 * evaluations of the same point move {@code lastDecisionId} and {@code evaluationCount} instead of opening a new
 * episode. The point is (decision, subject type, subject id, line, fulfilment group) — see {@code DecisionPoints}.
 */
public record Episode(String episodeId, String decisionId, String decisionName, String orderId, String lineId, String fulfillmentGroupId,
                       EpisodeStatus status, Double reward, Map<String, Double> rewardComponents, Integer policyVersion, Instant createdAt, Instant maturedAt,
                       String subjectType, String subjectId, String lastDecisionId, int evaluationCount) {

    /** A fresh episode for the first evaluation of a decision point. */
    public static Episode open(String episodeId, String decisionId, String decisionName, String orderId, String lineId, String fulfillmentGroupId,
                               Integer policyVersion, String subjectType, String subjectId, Instant createdAt) {
        return new Episode(episodeId, decisionId, decisionName, orderId, lineId, fulfillmentGroupId, EpisodeStatus.OPEN, null, Map.of(), policyVersion,
            createdAt, null, subjectType, subjectId, decisionId, 1);
    }

    public Episode withStatus(EpisodeStatus s, Instant maturedAt) {
        return new Episode(episodeId, decisionId, decisionName, orderId, lineId, fulfillmentGroupId, s, reward, rewardComponents, policyVersion, createdAt, maturedAt,
            subjectType, subjectId, lastDecisionId, evaluationCount);
    }

    public Episode withReward(double reward, Map<String, Double> components) {
        return new Episode(episodeId, decisionId, decisionName, orderId, lineId, fulfillmentGroupId, status, reward, components, policyVersion, createdAt, maturedAt,
            subjectType, subjectId, lastDecisionId, evaluationCount);
    }

    public String decisionPointKey() { return DecisionPoints.key(decisionName, subjectType, subjectId, orderId, lineId, fulfillmentGroupId); }
}
