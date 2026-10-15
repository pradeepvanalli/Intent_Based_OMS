package ai.intentoms.platform.ledger;

import java.util.Map;

/**
 * An event to append. Type names are dotted past-tense facts: "order.captured", "line.transitioned",
 * "decision.evaluated", "hold.placed". Payload is a JSON object; keep PII tokenised where not needed.
 */
public record NewEvent(String type, Map<String, Object> payload, Actor actor, String causationId, String correlationId, Map<String, Object> metadata) {
    public NewEvent(String type, Map<String, Object> payload, Actor actor) { this(type, payload, actor, null, null, Map.of()); }
    public NewEvent withCorrelation(String correlationId, String causationId) {
        return new NewEvent(type, payload, actor, causationId, correlationId, metadata);
    }
}
