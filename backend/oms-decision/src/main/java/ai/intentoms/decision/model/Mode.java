package ai.intentoms.decision.model;

/**
 * Invocation mode (FR-DEC-07, FR-DEC-09, FR-SIM-01). LIVE acts; SHADOW runs beside live without acting; SIMULATION and
 * REPLAY never act, escalate or cache but still persist records flagged with their mode so reports can compare.
 */
public enum Mode {
    LIVE, SHADOW, SIMULATION, REPLAY;

    /** Only LIVE evaluations may raise L3 escalations, write the result cache or be acted upon. */
    public boolean acts() { return this == LIVE; }
}
