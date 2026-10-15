package ai.intentoms.decision.model;

/**
 * Risk tier of a decision (PRD section 8 "Risk tiers"). The tier fixes the default confidence bands, the number of
 * approvers (GR-10, GR-14) and whether the model may ever act autonomously (T3 never acts).
 */
public enum Tier {
    /** Informational: classify, tag, summarise. Acts at confidence >= 0.5, else "unclassified". One approver. */
    T0,
    /** Reversible operational: re-source, carrier choice, disposition. Act >= 0.8, confirm 0.5-0.8. One approver + simulation. */
    T1,
    /** Customer-impacting: split vs hold, appeasement within limit, refund timing. Act only >= 0.9. Two approvers + simulation. */
    T2,
    /** Money-moving or irreversible: never autonomous; the model recommends, a person decides. Two approvers incl. risk owner. */
    T3;

    /** Default bands per the PRD tier table; T3 "never acts" is encoded as an unreachable act threshold. */
    public Bands defaultBands() {
        return switch (this) {
            case T0 -> new Bands(0.5, 0.5);
            case T1 -> new Bands(0.8, 0.5);
            case T2 -> new Bands(0.9, 0.5);
            case T3 -> Bands.neverAct(0.5);
        };
    }

    /** Approvers required to publish (GR-10): one for T0/T1, two distinct for T2/T3. */
    public int requiredApprovers() { return this == T2 || this == T3 ? 2 : 1; }

    public boolean neverActs() { return this == T3; }
}
