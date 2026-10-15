package ai.intentoms.analytics.decision;

import ai.intentoms.decision.model.Tier;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Per-version decision analytics (FR-ANL-02, UI contract item 14): volume, band and layer mix, override and escalation
 * rates, latency percentiles, cost, accuracy against labels, calibration curve and daily volume for every non-draft version.
 */
public record DecisionAnalytics(String name, Tier tier, List<VersionAnalytics> versions) {

    /**
     * @param bandMix      share of records per band (keys ACT, CONFIRM, ESCALATE; fractions summing to 1 when volume > 0)
     * @param layerMix     share of records per layer (keys L0_RULE, L1_SYSTEM_ONE, L2_LLM, L3_HUMAN, DEFAULT)
     * @param accuracy     labelled records judged correct / labelled; null when nothing is labelled
     * @param dailyVolume  one count per day for the last N days ending today (oldest first)
     * @param daily        the same series with explicit dates
     */
    public record VersionAnalytics(int version, String status, long volume, Map<String, Double> bandMix, Map<String, Double> layerMix, double overrideRate,
                                   double escalationRate, double latencyP50Ms, double latencyP95Ms, double costUsd, double costPerDecisionUsd, Double accuracy,
                                   double calibrationError, List<CalibrationPoint> calibration, List<Long> dailyVolume, List<DailyCount> daily) {}

    /** One of ten equal confidence bins: {@code confidence} is the mean stated confidence of the bin ({@code meanConfidence} is the same value under the UI's name). */
    public record CalibrationPoint(int bin, double lower, double upper, double confidence, double meanConfidence, double accuracy, long count) {}

    public record DailyCount(LocalDate date, long count) {}

    /** Studio list row (FR-ANL-02 overview). */
    public record OverviewRow(String name, Tier tier, String status, long volume, double overrideRate, double escalationRate, double costUsd) {}

    /** AI spend over a window (GR-17): per decision and provider, per day, against the tenant's daily budget when configured. */
    public record AiCostReport(int days, double totalUsd, Double dailyBudgetUsd, List<AiCostRow> byDecision, List<AiCostDay> daily) {}
    public record AiCostRow(String decision, String provider, long calls, double costUsd, long inputTokens, double costPerCallUsd) {}
    public record AiCostDay(LocalDate date, double costUsd, long calls) {}
}
