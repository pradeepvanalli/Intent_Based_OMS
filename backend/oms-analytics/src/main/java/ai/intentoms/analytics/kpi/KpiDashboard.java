package ai.intentoms.analytics.kpi;

import java.time.Instant;
import java.util.List;

/** Section 13 dashboard (FR-ANL-01): the range, every KPI tile in catalog order and when it was computed. */
public record KpiDashboard(Range range, List<Kpi> kpis, Instant generatedAt) {
    public record Range(String from, String to) {}

    public Kpi kpi(String key) { return kpis.stream().filter(k -> k.key().equals(key)).findFirst().orElse(null); }
}
