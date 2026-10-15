package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.platform.ledger.Actor;

import java.util.List;
import java.util.Map;

/**
 * Everything an {@link EscalationHandler} needs to open a human task (L3). The decision record with {@code recordId}
 * is committed in the same transaction as the escalation, so it is visible once the transaction completes.
 */
public record EscalationRequest(String recordId, String decision, int version, Tier tier, String subjectType, String subjectId, String correlationId,
                                Map<String, Object> input, Map<String, Object> state, Object recommendedValue, Map<String, Answer> answers, double confidence, Band band,
                                List<String> reasonCodes, String reason, Actor actor) {}
