package ai.intentoms.copilot.ops;

import java.util.Map;

/** A template with validated, typed parameters ready to run; {@code sql} is what the user sees. */
public record BoundQuery(OpsQueryTemplate template, String sql, Map<String, Object> parameters) {}
