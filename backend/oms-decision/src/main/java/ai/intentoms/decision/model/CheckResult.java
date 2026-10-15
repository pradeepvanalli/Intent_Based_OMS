package ai.intentoms.decision.model;

/** Outcome of one pre- or post-check CEL expression (GR-01). {@code error} is set when evaluation itself failed. */
public record CheckResult(String expression, boolean passed, String error) {}
