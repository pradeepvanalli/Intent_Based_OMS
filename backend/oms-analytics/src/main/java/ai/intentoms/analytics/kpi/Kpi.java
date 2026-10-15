package ai.intentoms.analytics.kpi;

import java.util.List;

/**
 * One dashboard tile (UI contract item 14). Rates are fractions (0.912 = 91.2%), money is USD, durations are minutes or
 * hours as stated by {@code unit}; {@code targetDirection} is {@code gte} (higher is better) or {@code lte} (lower is better).
 */
public record Kpi(String key, String label, double value, String unit, Double target, String targetDirection, List<Double> trend, Double delta, String definition) {}
