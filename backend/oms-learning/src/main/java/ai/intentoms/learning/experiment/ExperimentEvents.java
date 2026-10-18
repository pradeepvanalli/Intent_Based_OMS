package ai.intentoms.learning.experiment;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Experiment lifecycle events on the learning aggregate, with the same payload digest as {@link LearningEvents}:
 * {@code experiment.started}, {@code experiment.completed} (with its result) and {@code experiment.stopped}.
 */
@Component
public class ExperimentEvents {
    private final EventStore events;
    public ExperimentEvents(EventStore events) { this.events = events; }

    public void started(String experimentId, Actor actor, Map<String, Object> payload) { append(experimentId, "experiment.started", actor, payload); }
    public void completed(String experimentId, Actor actor, Map<String, Object> payload) { append(experimentId, "experiment.completed", actor, payload); }
    public void stopped(String experimentId, Actor actor, Map<String, Object> payload) { append(experimentId, "experiment.stopped", actor, payload); }

    private void append(String aggregateId, String type, Actor actor, Map<String, Object> payload) {
        Map<String, Object> withDigest = new LinkedHashMap<>(payload);
        withDigest.put("payloadDigest", Json.sha256(payload));
        events.append(LearningEvents.AGGREGATE, aggregateId, new NewEvent(type, withDigest, actor));
    }
}
