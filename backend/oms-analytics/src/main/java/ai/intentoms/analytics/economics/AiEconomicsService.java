package ai.intentoms.analytics.economics;

import ai.intentoms.analytics.economics.AiEconomics.DailyLayers;
import ai.intentoms.analytics.economics.AiEconomics.DecisionRow;
import ai.intentoms.analytics.economics.AiEconomics.LayerStats;
import ai.intentoms.analytics.economics.AiEconomicsRepository.DayLayerRow;
import ai.intentoms.analytics.economics.AiEconomicsRepository.DecisionAgg;
import ai.intentoms.analytics.economics.AiEconomicsRepository.LayerRow;
import ai.intentoms.analytics.economics.AiEconomicsRepository.Totals;
import ai.intentoms.analytics.kpi.KpiMath;
import ai.intentoms.analytics.kpi.KpiRange;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AI economics over a day range: decisions by layer, automation and override/escalation rates, AI spend per 1 000
 * decisions and per order, latency percentiles per layer, a per-decision table and the (clearly labelled) estimate of
 * analyst hours saved. Everything is derived from live decision records, so a tenant with no decisions gets zeros.
 */
@Service
public class AiEconomicsService {
    /** Tenant config key: minutes a person would spend on one T1/T2 decision (default {@value #DEFAULT_MINUTES}). */
    public static final String MINUTES_PER_HUMAN_DECISION = "analytics.minutes_per_human_decision";
    public static final double DEFAULT_MINUTES = 2.0;
    private static final String[] LAYERS = java.util.Arrays.stream(Layer.values()).map(Enum::name).toArray(String[]::new);

    private final AiEconomicsRepository repo;
    private final TenantConfigStore config;

    public AiEconomicsService(AiEconomicsRepository repo, TenantConfigStore config) {
        this.repo = repo;
        this.config = config;
    }

    public AiEconomics report(KpiRange range) {
        Instant from = range.startInclusive(), to = range.endExclusive();
        Totals t = repo.totals(from, to);
        double minutes = config.getDouble(MINUTES_PER_HUMAN_DECISION, DEFAULT_MINUTES);
        if (!(minutes > 0)) minutes = DEFAULT_MINUTES;
        long orders = repo.ordersPlaced(from, to);

        Map<String, LayerRow> layerRows = new HashMap<>();
        for (LayerRow r : repo.byLayer(from, to)) layerRows.put(r.layer(), r);
        List<LayerStats> byLayer = new ArrayList<>(LAYERS.length);
        for (String l : LAYERS) {
            LayerRow r = layerRows.get(l);
            byLayer.add(r == null ? new LayerStats(l, 0, 0, 0, 0)
                : new LayerStats(l, r.count(), KpiMath.round(KpiMath.rate(r.count(), t.volume()), 6), KpiMath.round(r.p50(), 3), KpiMath.round(r.p95(), 3)));
        }

        TreeMap<LocalDate, Map<String, Long>> days = new TreeMap<>();
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) days.put(d, AiEconomicsRepository.emptyLayers(LAYERS));
        for (DayLayerRow r : repo.dailyByLayer(from, to)) {
            Map<String, Long> m = days.get(r.day());
            if (m != null) m.merge(r.layer(), r.count(), Long::sum);
        }
        List<DailyLayers> daily = new ArrayList<>(days.size());
        days.forEach((d, m) -> daily.add(new DailyLayers(d, m, m.values().stream().mapToLong(Long::longValue).sum())));

        Map<String, Double> golden = repo.goldenAccuracyByDecision();
        List<DecisionRow> decisions = new ArrayList<>();
        for (DecisionAgg a : repo.byDecision(from, to)) {
            decisions.add(new DecisionRow(a.decision(), a.tier(), a.volume(), KpiMath.round(KpiMath.rate(a.acting(), a.volume()), 6), KpiMath.round(a.avgConfidence(), 4),
                KpiMath.round(a.volume() == 0 ? 0 : a.costUsd() / a.volume() * 1000, 6), KpiMath.round(a.p95(), 3), KpiMath.round(KpiMath.rate(a.overrides(), a.acting()), 6), golden.get(a.decision())));
        }

        double hours = t.avoided() * minutes / 60.0;
        List<String> assumptions = List.of(
            "Only live decision records count (shadow, simulation and replay runs are excluded).",
            "Automation rate = decisions that acted autonomously (band ACT) / all decisions.",
            "Human decisions avoided = acting decisions on T1/T2 decisions; T0 is informational and T3 never acts autonomously.",
            "Override rate = human overrides / acting decisions; escalation rate = decisions that required a person / all decisions.",
            "AI spend is the provider cost recorded on each decision record; cost per order divides it by orders placed in the range.",
            "Estimated analyst hours saved = human decisions avoided × " + trim(minutes) + " min (tenant config " + MINUTES_PER_HUMAN_DECISION + "). It is an estimate, not a measurement.");

        return new AiEconomics(new AiEconomics.Range(range.from(), range.to(), range.days()), t.volume(), t.acting(), KpiMath.round(KpiMath.rate(t.acting(), t.volume()), 6), t.avoided(),
            t.overrides(), KpiMath.round(KpiMath.rate(t.overrides(), t.acting()), 6), t.escalations(), KpiMath.round(KpiMath.rate(t.escalations(), t.volume()), 6),
            KpiMath.round(t.costUsd(), 6), KpiMath.round(t.volume() == 0 ? 0 : t.costUsd() / t.volume() * 1000, 6), orders, KpiMath.round(orders == 0 ? 0 : t.costUsd() / orders, 6),
            minutes, KpiMath.round(hours, 4), byLayer, daily, decisions, assumptions);
    }

    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }
}
