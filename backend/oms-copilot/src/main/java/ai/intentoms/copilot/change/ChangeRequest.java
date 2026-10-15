package ai.intentoms.copilot.change;

import java.util.Map;

/**
 * A plain-language configuration request (FR-CFG-01). {@code scope} is an optional hint (e.g. "returns", "sourcing",
 * "state model"); {@code context} carries optional structured facts the user supplies (ticket id, affected channel...).
 */
public record ChangeRequest(String text, String scope, Map<String, Object> context) {
    public ChangeRequest {
        if (text == null || text.isBlank()) throw new ai.intentoms.platform.error.ValidationException("Describe the change in plain language ('text' is required)");
        text = text.trim();
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
