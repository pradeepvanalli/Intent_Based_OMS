package ai.intentoms.decision.model;

import java.util.List;
import java.util.Map;

/**
 * Impact diff of a draft against the live version over recent records (FR-PRM-03): flips, band mix before/after,
 * escalation, cost and latency deltas, with sample flipped record ids.
 */
public record ImpactReport(String decision, int liveVersion, int draftVersion, int days, int sampled, int flips, double flipRate,
                           Map<String, Integer> bandMixBefore, Map<String, Integer> bandMixAfter, int escalationsBefore, int escalationsAfter,
                           double costBeforeUsd, double costAfterUsd, double avgLatencyBeforeMs, double avgLatencyAfterMs,
                           List<Flip> sampleFlips, List<String> textChanges) {
    public record Flip(String liveRecordId, String simulationRecordId, Object before, Object after, Band bandBefore, Band bandAfter) {}
}
