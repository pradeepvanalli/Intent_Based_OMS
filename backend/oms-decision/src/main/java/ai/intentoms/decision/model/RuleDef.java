package ai.intentoms.decision.model;

/**
 * L0 hard rule (FR-DEC-03). {@code when} is a CEL expression over {@code input} (and the standard roots found in it);
 * {@code then} is a literal value, "escalate" or "block". Lower priority runs first; first match wins.
 */
public record RuleDef(String id, String when, String then, String reason, int priority) {
    public static final String ESCALATE = "escalate";
    public static final String BLOCK = "block";
    public boolean escalates() { return ESCALATE.equals(then); }
    public boolean blocks() { return BLOCK.equals(then); }
}
