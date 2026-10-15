package ai.intentoms.simulation.replay;

import ai.intentoms.sourcing.model.SimulationOverrides;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The diff of one order replayed through current or proposed decisions and state model (FR-SIM-01; UI contract item 13
 * {@code ReplayResult{decisions[{before, after, changed}]}}).
 *
 * @param eventsReplayed ledger events folded to rebuild the order
 * @param stateBefore    header status recorded in the ledger
 * @param stateAfter     header status under the proposed state model (equal to {@code stateBefore} when none was given)
 * @param stateModel     null unless a proposed state-model version was requested
 * @param sourcing       null when the order was never sourced and no sourcing override was requested
 */
public record ReplayResult(String orderId, Instant ranAt, int eventsReplayed, String stateBefore, String stateAfter, List<DecisionReplay> decisions,
                           StateModelReplayResult stateModel, SourcingReplay sourcing, Summary summary) {
    public ReplayResult { decisions = decisions == null ? List.of() : List.copyOf(decisions); }

    /**
     * One recorded decision re-run with its stored input.
     *
     * @param recordId       the LIVE record replayed
     * @param replayRecordId the REPLAY-mode record the re-run persisted
     * @param version        alias of {@code versionBefore} (UI contract)
     * @param flipped        value differs; {@code bandChanged} band differs; {@code changed} any of value, band, layer, action
     */
    public record DecisionReplay(String recordId, String replayRecordId, String decision, String subjectId, int version, int versionBefore, int versionAfter, Outcome before, Outcome after,
                                 boolean changed, boolean flipped, boolean bandChanged, double costDeltaUsd, long latencyMs) {
        public static DecisionReplay of(String recordId, String replayRecordId, String decision, String subjectId, int versionBefore, int versionAfter, Outcome before, Outcome after) {
            return new DecisionReplay(recordId, replayRecordId, decision, subjectId, versionBefore, versionBefore, versionAfter, before, after, after.changedFrom(before),
                after.flippedFrom(before), after.bandChangedFrom(before), round(after.costUsd() - before.costUsd()), after.latencyMs());
        }
    }

    /** Recorded plan vs a fresh plan under the overrides (FR-SIM-01, FR-SIM-03). */
    public record SourcingReplay(PlanSummary before, PlanSummary after, SimulationOverrides overrides, BigDecimal costDelta, Integer packageDelta, Double onTimeDelta, Double marginDelta,
                                 boolean nodesChanged, String error) {
        public static SourcingReplay of(PlanSummary before, PlanSummary after, SimulationOverrides overrides, String error) {
            BigDecimal costDelta = before == null || after == null || before.totalCost() == null || after.totalCost() == null ? null : after.totalCost().subtract(before.totalCost());
            Integer pkgDelta = before == null || after == null ? null : after.packages() - before.packages();
            Double onTime = before == null || after == null || before.onTimeProbability() == null || after.onTimeProbability() == null ? null : round(after.onTimeProbability() - before.onTimeProbability());
            Double margin = before == null || after == null || before.marginRatio() == null || after.marginRatio() == null ? null : round(after.marginRatio() - before.marginRatio());
            boolean nodes = before != null && after != null && !before.nodeIds().equals(after.nodeIds());
            return new SourcingReplay(before, after, overrides, costDelta, pkgDelta, onTime, margin, nodes, error);
        }
    }

    public record Summary(int decisionsReplayed, int flipped, int bandChanged, int changed, int blockedTransitions, double costDeltaUsd) {}

    static double round(double d) { return Math.round(d * 1_000_000d) / 1_000_000d; }
}
