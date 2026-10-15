package ai.intentoms.decision.model;

/**
 * Confidence thresholds (GR-02): confidence >= actGte acts; >= confirmGte confirms; otherwise escalates.
 * An actGte above 1.0 can never be reached and encodes "never acts" (tier T3).
 */
public record Bands(double actGte, double confirmGte) {
    public static final double NEVER = 2.0;

    public Bands {
        if (confirmGte < 0 || confirmGte > 1) throw new IllegalArgumentException("confirm.confidence_gte must be within [0,1]");
        if (actGte < 0) throw new IllegalArgumentException("act.confidence_gte must be >= 0");
        if (actGte <= 1.0 && actGte < confirmGte) throw new IllegalArgumentException("act.confidence_gte must be >= confirm.confidence_gte");
    }

    public static Bands neverAct(double confirmGte) { return new Bands(NEVER, confirmGte); }

    public boolean neverActs() { return actGte > 1.0; }

    public Band of(double confidence) {
        if (!neverActs() && confidence >= actGte) return Band.ACT;
        if (confidence >= confirmGte) return Band.CONFIRM;
        return Band.ESCALATE;
    }
}
