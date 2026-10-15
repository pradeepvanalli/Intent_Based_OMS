package ai.intentoms.simulation.replay;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.studio.Calibration;

/**
 * The comparable part of a decision evaluation (FR-SIM-01 diff, FR-PRM-03 impact). {@code acts} mirrors
 * {@link DecisionResult#acts()}: act band and no human required; {@code escalated} is what the batch report counts as an
 * escalation (FR-SIM-02).
 */
public record Outcome(Object value, Band band, double confidence, Layer layer, String action, boolean requiresHuman, double costUsd, long latencyMs) {
    public static Outcome of(DecisionRecord r) {
        return new Outcome(r.value(), r.band(), r.confidence(), r.layer(), r.action(), r.requiresHuman(), r.costUsd(), r.latencyMs());
    }

    public static Outcome of(DecisionResult r) {
        return new Outcome(r.value(), r.band(), r.confidence(), r.layer(), r.action(), r.requiresHuman(), r.costUsd(), r.latencyMs());
    }

    public boolean acts() { return DecisionResult.ACT.equals(action) && !requiresHuman; }

    public boolean escalated() { return requiresHuman || band == Band.ESCALATE; }

    /** Value differs (tolerant number/string/boolean comparison, same rules as the studio impact diff). */
    public boolean flippedFrom(Outcome other) { return !Calibration.valueEquals(other.value, value); }

    public boolean bandChangedFrom(Outcome other) { return band != other.band; }

    public boolean changedFrom(Outcome other) {
        return flippedFrom(other) || bandChangedFrom(other) || layer != other.layer || !java.util.Objects.equals(action, other.action) || requiresHuman != other.requiresHuman;
    }
}
