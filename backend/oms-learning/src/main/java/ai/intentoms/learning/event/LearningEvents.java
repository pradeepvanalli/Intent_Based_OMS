package ai.intentoms.learning.event;

import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends the eight SOO lifecycle events (requirements doc §9.2) in the same dotted-past-tense convention as the
 * rest of the ledger, each carrying a SHA-256 digest of its own payload (GV-04) so the event itself is tamper-
 * evident without needing the whole-ledger digest the evidence pack computes separately.
 */
@Component
public class LearningEvents {
    public static final String AGGREGATE = "learning";

    private final EventStore events;
    public LearningEvents(EventStore events) { this.events = events; }

    public void episodeMatured(String episodeId, Actor actor, Map<String, Object> payload) { append(episodeId, "episode.matured", actor, payload); }
    public void factLearned(String factId, Actor actor, Map<String, Object> payload) { append(factId, "fact.learned", actor, payload); }
    public void hypothesisRaised(String hypothesisId, Actor actor, Map<String, Object> payload) { append(hypothesisId, "hypothesis.raised", actor, payload); }
    public void proposalDrafted(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.drafted", actor, payload); }
    public void proposalEvaluated(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.evaluated", actor, payload); }
    public void experimentStarted(String experimentId, Actor actor, Map<String, Object> payload) { append(experimentId, "experiment.started", actor, payload); }
    public void proposalPromoted(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.promoted", actor, payload); }
    public void proposalRolledBack(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.rolled_back", actor, payload); }

    private void append(String aggregateId, String type, Actor actor, Map<String, Object> payload) {
        Map<String, Object> withDigest = new LinkedHashMap<>(payload);
        withDigest.put("payloadDigest", Json.sha256(payload));
        events.append(AGGREGATE, aggregateId, new NewEvent(type, withDigest, actor));
    }
}
