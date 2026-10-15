package ai.intentoms.analytics.economics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * "AI Economics" report for a calendar-day range (UTC), computed from live {@code decision_record} rows joined with the
 * decision definition's tier, plus the order count for cost-per-order. Every rate is a fraction in [0, 1]; every list is
 * complete (all layers, all days of the range) so a UI can render it without special-casing empty data.
 *
 * @param humanDecisionsAvoided        acting decisions on T1/T2 decisions — the ones a person would otherwise have made
 * @param estimatedAnalystHoursSaved   humanDecisionsAvoided × minutesPerHumanDecision / 60 (an estimate; see assumptions)
 * @param minutesPerHumanDecision      tenant config {@code analytics.minutes_per_human_decision} (default 2)
 */
public record AiEconomics(Range range, long totalDecisions, long actingDecisions, double automationRate, long humanDecisionsAvoided,
                          long overrides, double overrideRate, long escalations, double escalationRate,
                          double totalAiCostUsd, double costPer1kDecisionsUsd, long orders, double costPerOrderUsd,
                          double minutesPerHumanDecision, double estimatedAnalystHoursSaved,
                          List<LayerStats> byLayer, List<DailyLayers> daily, List<DecisionRow> decisions, List<String> assumptions) {

    public record Range(LocalDate from, LocalDate to, int days) {}

    /** One entry per {@code Layer} enum value (L0_RULE, L1_SYSTEM_ONE, L2_LLM, L3_HUMAN, DEFAULT), zeros when unused. */
    public record LayerStats(String layer, long count, double share, double latencyP50Ms, double latencyP95Ms) {}

    /** One entry per day of the range; {@code byLayer} always carries every layer key. */
    public record DailyLayers(LocalDate date, Map<String, Long> byLayer, long total) {}

    /**
     * @param goldenAccuracy accuracy of the live version's last golden-set evaluation, null when never evaluated
     */
    public record DecisionRow(String name, String tier, long volume, double automationRate, double avgConfidence, double costPer1kUsd,
                              double latencyP95Ms, double overrideRate, Double goldenAccuracy) {}
}
