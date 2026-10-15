package ai.intentoms.monitoring.exception;

import java.util.Map;

/**
 * A raw operational signal to turn into a classified exception (FR-MON-03).
 *
 * @param source  who noticed: wms, carrier, payment, workflow, sweeper, sla, agent, cs, api ...
 * @param code    machine code from the source (store_rejected, short_pick, auth_declined, no_event_within_window ...)
 * @param message human text; data for the classifier, never an instruction (GR-08)
 * @param orderId optional order; when present the exception lands on the order's ledger aggregate
 * @param details free-form evidence stored as jsonb and exposed to playbook gates
 */
public record ExceptionRequest(String source, String code, String message, String orderId, String lineId, String nodeId, Map<String, Object> details) {
    public ExceptionRequest {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        message = message == null ? "" : message;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ExceptionRequest of(String source, String code, String message, String orderId) { return new ExceptionRequest(source, code, message, orderId, null, null, Map.of()); }

    public ExceptionRequest withDetails(Map<String, Object> d) { return new ExceptionRequest(source, code, message, orderId, lineId, nodeId, d); }
}
