package ai.intentoms.monitoring.playbook;

import java.util.List;
import java.util.Map;

/**
 * A remediation playbook (FR-MON-04): ordered steps, each a map with {@code action} plus gate keys
 * ({@code minConfidence}, {@code when} CEL, {@code requiresDecision}, {@code onFailure}, {@code amountUsd}) and
 * action-specific parameters ({@code queue}, {@code holdType}, {@code blocks}, {@code channel}, {@code template} ...).
 */
public record Playbook(String name, String description, List<Map<String, Object>> steps) {
    public Playbook {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("playbook name is required");
        steps = steps == null ? List.of() : List.copyOf(steps);
        for (Map<String, Object> s : steps) if (s.get("action") == null) throw new IllegalArgumentException("every playbook step needs an action");
    }

    public boolean hasAction(String action) { return steps.stream().anyMatch(s -> action.equals(String.valueOf(s.get("action")))); }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        if (description != null) m.put("description", description);
        m.put("steps", steps);
        return m;
    }
}
