package ai.intentoms.platform.ledger;

import java.time.Instant;
import java.util.Map;

/** Immutable, append-only ledger entry (FR-SM-11, section 10 "Event"). Serialises to CloudEvents 1.0. */
public record Event(
    String id,
    String tenantId,
    String aggregateType,
    String aggregateId,
    long sequence,
    String type,
    Map<String, Object> payload,
    Actor actor,
    String causationId,
    String correlationId,
    Map<String, Object> metadata,
    Instant occurredAt
) {
    public Map<String, Object> toCloudEvent() {
        var ce = new java.util.LinkedHashMap<String, Object>();
        ce.put("specversion", "1.0");
        ce.put("id", id);
        ce.put("source", "/tenants/" + tenantId + "/" + aggregateType + "/" + aggregateId);
        ce.put("type", "ai.intentoms." + type);
        ce.put("subject", aggregateId);
        ce.put("time", occurredAt.toString());
        ce.put("datacontenttype", "application/json");
        ce.put("tenantid", tenantId);
        ce.put("sequence", sequence);
        ce.put("actor", actor.ref());
        if (causationId != null) ce.put("causationid", causationId);
        if (correlationId != null) ce.put("correlationid", correlationId);
        ce.put("data", payload);
        return ce;
    }
}
