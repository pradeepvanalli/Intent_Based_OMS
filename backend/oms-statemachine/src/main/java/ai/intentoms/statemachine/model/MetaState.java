package ai.intentoms.statemachine.model;

/**
 * System meta-states every tenant-defined state maps to so cross-tenant tooling (pipeline view, stuck sweeper,
 * analytics) can reason about any model (FR-SM-01). A model must have exactly one {@link #CREATED} state and at
 * least one {@link #TERMINAL} state (FR-SM-10).
 */
public enum MetaState {
    CREATED, ACTIVE, TERMINAL;

    /** Case-insensitive parse; {@code null} or blank means {@link #ACTIVE}. */
    public static MetaState parse(String s) {
        if (s == null || s.isBlank()) return ACTIVE;
        return switch (s.trim().toLowerCase()) {
            case "created", "initial" -> CREATED;
            case "terminal", "final" -> TERMINAL;
            case "active" -> ACTIVE;
            default -> throw new IllegalArgumentException("Unknown meta state '" + s + "' (expected Created, Active or Terminal)");
        };
    }
}
