package ai.intentoms.analytics.decision;

import ai.intentoms.analytics.decision.DecisionAnalytics.AiCostReport;
import ai.intentoms.analytics.decision.DecisionAnalytics.CalibrationPoint;
import ai.intentoms.analytics.decision.DecisionAnalytics.DailyCount;
import ai.intentoms.analytics.decision.DecisionAnalytics.OverviewRow;
import ai.intentoms.analytics.decision.DecisionAnalytics.VersionAnalytics;
import ai.intentoms.analytics.decision.DecisionStatsRepository.WindowStats;
import ai.intentoms.analytics.kpi.KpiMath;
import ai.intentoms.analytics.kpi.KpiRange;
import ai.intentoms.decision.analytics.DecisionAnalyticsRepository;
import ai.intentoms.decision.analytics.DecisionAnalyticsRepository.VersionStats;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DecisionDefinitionRepository.Row;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.EvaluationReport.CalibrationBin;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decision analytics per version (FR-ANL-02): built on the decision module's {@code DecisionAnalyticsRepository} (all-time
 * per-version volume, band/layer mix, override rate, calibration, latency, cost) joined with the definition's tier and
 * status, plus windowed daily volume. Also the studio overview list and the AI cost report (GR-17).
 */
@Service
public class DecisionAnalyticsService {
    private static final List<String> STATUS_PRECEDENCE = List.of("live", "canary", "shadow", "approved", "pending_approval", "draft", "retired");

    private final DecisionAnalyticsRepository analytics;
    private final DecisionDefinitionRepository definitions;
    private final DecisionStatsRepository stats;
    private final TenantConfigStore config;
    private final TimeSource time;

    public DecisionAnalyticsService(DecisionAnalyticsRepository analytics, DecisionDefinitionRepository definitions, DecisionStatsRepository stats, TenantConfigStore config, TimeSource time) {
        this.analytics = analytics;
        this.definitions = definitions;
        this.stats = stats;
        this.config = config;
        this.time = time;
    }

    /** Analytics for every non-draft version of {@code name}, newest first; the daily series covers the last {@code days} days. */
    public DecisionAnalytics forDecision(String name, int days) {
        List<Row> rows = definitions.versions(name);
        if (rows.isEmpty()) throw new NotFoundException("Decision " + name + " not found", Map.of("name", name));
        int window = Math.max(1, Math.min(days, 365));
        LocalDate today = KpiRange.dayOf(time.now());
        KpiRange range = KpiRange.lastDays(today, window);
        Map<Integer, VersionStats> byVersion = new HashMap<>();
        for (VersionStats s : analytics.perVersion(name)) byVersion.put(s.version(), s);
        Map<Integer, List<DailyCount>> daily = stats.dailyByVersion(name, range.startInclusive());

        Map<Integer, String> statuses = new TreeMap<>(Comparator.reverseOrder());
        for (Row r : rows) if (!"draft".equals(r.definition().status())) statuses.put(r.definition().version(), r.definition().status());
        for (Integer v : byVersion.keySet()) statuses.putIfAbsent(v, "unknown");

        List<VersionAnalytics> versions = new ArrayList<>();
        statuses.forEach((version, status) -> versions.add(version(version, status, byVersion.get(version), daily.getOrDefault(version, List.of()), range)));
        return new DecisionAnalytics(name, rows.get(0).definition().tier(), versions);
    }

    /** One row per decision for the studio list: tier, effective status and windowed volume/override/escalation/cost. */
    public List<OverviewRow> overview(int days) {
        Instant from = time.now().minus(Duration.ofDays(Math.max(1, Math.min(days, 365))));
        Map<String, WindowStats> windowed = stats.overview(from);
        List<OverviewRow> out = new ArrayList<>();
        for (String name : definitions.names()) {
            List<Row> rows = definitions.versions(name);
            if (rows.isEmpty()) continue;
            Tier tier = rows.get(0).definition().tier();
            String status = rows.stream().map(r -> r.definition().status()).min(Comparator.comparingInt(DecisionAnalyticsService::statusRank)).orElse("draft");
            WindowStats w = windowed.get(name);
            long volume = w == null ? 0 : w.volume();
            out.add(new OverviewRow(name, tier, status, volume, w == null ? 0 : KpiMath.round(KpiMath.rate(w.overrides(), volume), 6),
                w == null ? 0 : KpiMath.round(KpiMath.rate(w.escalations(), volume), 6), w == null ? 0 : KpiMath.round(w.costUsd(), 6)));
        }
        return out;
    }

    /** Provider spend over the last {@code days} days per decision/provider and per day against the configured daily budget (GR-17). */
    public AiCostReport aiCost(int days) {
        int window = Math.max(1, Math.min(days, 365));
        KpiRange range = KpiRange.lastDays(KpiRange.dayOf(time.now()), window);
        var byDecision = stats.spendByDecision(range.from(), range.to());
        var daily = stats.spendByDay(range.from(), range.to());
        double total = byDecision.stream().mapToDouble(DecisionAnalytics.AiCostRow::costUsd).sum();
        Double budget = config.get(ConfigKeys.AI_BUDGET_USD_PER_DAY).map(v -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString())).orElse(null);
        return new AiCostReport(window, KpiMath.round(total, 6), budget, byDecision, daily);
    }

    private static VersionAnalytics version(int version, String status, VersionStats s, List<DailyCount> daily, KpiRange range) {
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (DailyCount d : daily) byDay.put(d.date(), d.count());
        List<Long> series = new ArrayList<>(range.days());
        List<DailyCount> dated = new ArrayList<>(range.days());
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
            long n = byDay.getOrDefault(d, 0L);
            series.add(n);
            dated.add(new DailyCount(d, n));
        }
        if (s == null) {
            return new VersionAnalytics(version, status, 0, mix(Map.of(), 0, Band.values()), mix(Map.of(), 0, Layer.values()), 0, 0, 0, 0, 0, 0, null, 0, points(List.of()), series, dated);
        }
        long labelled = 0, correct = 0;
        for (CalibrationBin b : s.calibration()) { labelled += b.count(); correct += Math.round(b.accuracy() * b.count()); }
        return new VersionAnalytics(version, status, s.volume(), mix(s.bandMix(), s.volume(), Band.values()), mix(s.layerMix(), s.volume(), Layer.values()),
            KpiMath.round(s.overrideRate(), 6), KpiMath.round(KpiMath.rate(s.escalations(), s.volume()), 6), KpiMath.round(s.latencyP50Ms(), 3), KpiMath.round(s.latencyP95Ms(), 3),
            KpiMath.round(s.costTotalUsd(), 6), KpiMath.round(s.costAvgUsd(), 8), labelled == 0 ? null : KpiMath.round(correct / (double) labelled, 6), s.calibrationError(),
            points(s.calibration()), series, dated);
    }

    private static <E extends Enum<E>> Map<String, Double> mix(Map<String, Long> counts, long volume, E[] keys) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (E k : keys) out.put(k.name(), KpiMath.round(KpiMath.rate(counts.getOrDefault(k.name(), 0L), volume), 6));
        return out;
    }

    private static List<CalibrationPoint> points(List<CalibrationBin> bins) {
        List<CalibrationPoint> out = new ArrayList<>(10);
        if (bins.isEmpty()) {
            for (int b = 0; b < 10; b++) out.add(new CalibrationPoint(b, b / 10.0, (b + 1) / 10.0, 0, 0, 0, 0));
            return out;
        }
        int i = 0;
        for (CalibrationBin b : bins) out.add(new CalibrationPoint(i++, b.lower(), b.upper(), b.meanConfidence(), b.meanConfidence(), b.accuracy(), b.count()));
        return out;
    }

    private static int statusRank(String status) {
        int i = STATUS_PRECEDENCE.indexOf(status);
        return i < 0 ? STATUS_PRECEDENCE.size() : i;
    }
}
