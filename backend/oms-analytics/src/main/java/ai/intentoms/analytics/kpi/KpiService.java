package ai.intentoms.analytics.kpi;

import ai.intentoms.analytics.config.AnalyticsProperties;
import ai.intentoms.analytics.kpi.KpiCatalog.Kind;
import ai.intentoms.analytics.kpi.KpiCatalog.KpiDef;
import ai.intentoms.analytics.kpi.KpiRepository.LabelledRecord;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Section 13 KPI dashboard (FR-ANL-01, UI contract item 14). Values are computed from the source tables for the live
 * window and from {@code kpi_daily} for older days, cached per tenant and range for a few seconds (the control tower polls
 * every 5 s). Each tile carries a daily trend over the range (at most {@code maxTrendPoints}), the delta against the
 * preceding period of equal length and the section 13 target, which for "vs baseline" KPIs is derived from the tenant's
 * recorded baseline ({@code kpi.baseline.<key>}) and can be pinned with {@code kpi.target.<key>}.
 */
@Service
public class KpiService {
    public static final String CONFIG_BASELINE_PREFIX = "kpi.baseline.";
    public static final String CONFIG_TARGET_PREFIX = "kpi.target.";

    private final KpiRepository repo;
    private final KpiSeriesComputer computer;
    private final KpiDailyRepository daily;
    private final TenantConfigStore config;
    private final DecisionDefinitionRepository definitions;
    private final TimeSource time;
    private final Metrics metrics;
    private final AnalyticsProperties props;
    private final Cache<String, KpiDashboard> cache;

    public KpiService(KpiRepository repo, KpiSeriesComputer computer, KpiDailyRepository daily, TenantConfigStore config, DecisionDefinitionRepository definitions,
                      TimeSource time, Metrics metrics, AnalyticsProperties props) {
        this.repo = repo;
        this.computer = computer;
        this.daily = daily;
        this.config = config;
        this.definitions = definitions;
        this.time = time;
        this.metrics = metrics;
        this.props = props;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(props.cacheTtlMs())).maximumSize(10_000).build();
    }

    /** The dashboard for the last 30 days (UTC). */
    public KpiDashboard dashboard() { return dashboard(KpiRange.lastDays(today(), 30)); }

    /** The dashboard for {@code range}; served from the per-tenant cache when computed within the TTL. */
    public KpiDashboard dashboard(KpiRange range) {
        String key = TenantContext.require() + "|" + range;
        return cache.get(key, k -> metrics.time("oms.analytics.kpis", () -> compute(range)));
    }

    /** Drop cached dashboards of the current tenant (after a roll-up or a config change). */
    public void invalidate() {
        String prefix = TenantContext.require() + "|";
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    private KpiDashboard compute(KpiRange range) {
        LocalDate today = today();
        KpiRange window = range.withPrior();
        Instant now = time.now();
        List<Kpi> out = new ArrayList<>(KpiCatalog.ALL.size());
        List<LabelledRecord> labelled = null;
        for (KpiDef def : KpiCatalog.ALL) {
            Kpi kpi = switch (def.key()) {
                case KpiCatalog.TOUCHLESS_TODAY -> touchlessToday(def, range, today);
                case KpiCatalog.DECISION_ACCURACY, KpiCatalog.CALIBRATION_ERROR -> {
                    if (labelled == null) labelled = computer.labelled(window);
                    yield decisionQuality(def, range, labelled);
                }
                default -> switch (def.kind()) {
                    case RATIO -> ratio(def, range, series(def.key(), window));
                    case MEDIAN -> median(def, range, series(def.key(), window));
                    case GAUGE -> gauge(def, range, today);
                };
            };
            out.add(kpi);
        }
        return new KpiDashboard(new KpiDashboard.Range(range.from().toString(), range.to().toString()), List.copyOf(out), now);
    }

    // ---- per kind ------------------------------------------------------------------------------------------------

    private Kpi ratio(KpiDef def, KpiRange range, List<DayPoint> points) {
        List<DayPoint> cur = KpiMath.within(points, range), prior = KpiMath.within(points, range.prior());
        double value = KpiMath.denominator(cur) > 0 ? KpiMath.value(cur) : def.whenEmpty();
        Double priorValue = KpiMath.denominator(prior) > 0 ? KpiMath.value(prior) : null;
        return tile(def, value, KpiMath.trend(cur, range, props.maxTrendPoints(), value), KpiMath.delta(value, priorValue), range);
    }

    private Kpi median(KpiDef def, KpiRange range, List<DayPoint> points) {
        OptionalDouble exact = exactMedian(def.key(), range), priorExact = exactMedian(def.key(), range.prior());
        double value = exact.orElse(def.whenEmpty());
        Double priorValue = priorExact.isPresent() ? priorExact.getAsDouble() : null;
        return tile(def, value, KpiMath.trend(KpiMath.within(points, range), range, props.maxTrendPoints(), value), exact.isPresent() ? KpiMath.delta(value, priorValue) : null, range);
    }

    private Kpi gauge(KpiDef def, KpiRange range, LocalDate today) {
        double value = switch (def.key()) {
            case KpiCatalog.OPEN_ORDERS -> repo.openOrders();
            case KpiCatalog.EXCEPTIONS_OPEN -> repo.exceptionsOpen();
            default -> throw new IllegalStateException(def.key());
        };
        Map<LocalDate, Double> snapshots = daily.readValues(def.key(), range.prior().from(), range.to());
        List<DayPoint> points = new ArrayList<>();
        snapshots.forEach((d, v) -> { if (!d.equals(today)) points.add(new DayPoint(d, v, 1)); });
        if (range.contains(today)) points.add(new DayPoint(today, value, 1));
        Double yesterday = snapshots.get(today.minusDays(1));
        return tile(def, value, KpiMath.trend(points, range, props.maxTrendPoints(), value), KpiMath.delta(value, yesterday), range);
    }

    private Kpi touchlessToday(KpiDef def, KpiRange range, LocalDate today) {
        List<DayPoint> recent = computer.live(KpiCatalog.TOUCHLESS_RATE, new KpiRange(today.minusDays(6), today));
        List<DayPoint> todayPoints = KpiMath.within(recent, KpiRange.single(today)), yesterdayPoints = KpiMath.within(recent, KpiRange.single(today.minusDays(1)));
        double value = KpiMath.denominator(todayPoints) > 0 ? KpiMath.value(todayPoints) : def.whenEmpty();
        Double yesterday = KpiMath.denominator(yesterdayPoints) > 0 ? KpiMath.value(yesterdayPoints) : null;
        KpiRange week = new KpiRange(today.minusDays(6), today);
        return tile(def, value, KpiMath.trend(recent, week, 7, value), KpiMath.delta(value, yesterday), range);
    }

    /** Decision accuracy and calibration error from the same labelled records; both headline values are exact over the range. */
    private Kpi decisionQuality(KpiDef def, KpiRange range, List<LabelledRecord> labelled) {
        List<LabelledRecord> cur = new ArrayList<>(), prior = new ArrayList<>();
        KpiRange priorRange = range.prior();
        for (LabelledRecord r : labelled) {
            if (range.contains(r.day())) cur.add(r); else if (priorRange.contains(r.day())) prior.add(r);
        }
        boolean accuracy = def.key().equals(KpiCatalog.DECISION_ACCURACY);
        List<DayPoint> points = accuracy ? KpiSeriesComputer.accuracyPoints(cur) : KpiSeriesComputer.calibrationPoints(cur);
        double value = cur.isEmpty() ? def.whenEmpty() : accuracy ? KpiMath.value(points) : KpiSeriesComputer.calibrationError(cur);
        Double priorValue = prior.isEmpty() ? null : accuracy ? KpiMath.value(KpiSeriesComputer.accuracyPoints(prior)) : KpiSeriesComputer.calibrationError(prior);
        return tile(def, value, KpiMath.trend(points, range, props.maxTrendPoints(), value), cur.isEmpty() ? null : KpiMath.delta(value, priorValue), range);
    }

    // ---- series resolution ---------------------------------------------------------------------------------------

    /** Daily points over {@code window}: live for the last {@code liveWindowDays}, {@code kpi_daily} before that. */
    List<DayPoint> series(String key, KpiRange window) {
        LocalDate liveFrom = today().minusDays(props.liveWindowDays() - 1L);
        if (!window.from().isBefore(liveFrom)) return computer.live(key, window);
        LocalDate histTo = window.to().isBefore(liveFrom) ? window.to() : liveFrom.minusDays(1);
        List<DayPoint> points = new ArrayList<>(daily.read(key, window.from(), histTo));
        if (!window.to().isBefore(liveFrom)) points.addAll(computer.live(key, new KpiRange(liveFrom, window.to())));
        return points;
    }

    private OptionalDouble exactMedian(String key, KpiRange range) {
        return switch (key) {
            case KpiCatalog.EXCEPTION_RESOLUTION_MEDIAN -> repo.exceptionResolutionMedian(range);
            case KpiCatalog.POLICY_LEAD_TIME -> repo.policyLeadTimeMedian(range);
            default -> throw new IllegalStateException(key);
        };
    }

    // ---- targets -------------------------------------------------------------------------------------------------

    private Kpi tile(KpiDef def, double value, List<Double> trend, Double delta, KpiRange range) {
        return new Kpi(def.key(), def.label(), KpiMath.round(value, 6), def.unit(), target(def, range), def.targetDirection(), trend, delta, def.definition());
    }

    /**
     * Section 13 target: pinned by {@code kpi.target.<key>}; else the absolute target; else baseline x (1 +- pct) when the
     * tenant recorded {@code kpi.baseline.<key>}; decision accuracy uses the live definitions' publish gate (FR-PRM-05);
     * AI cost per order uses the tenant's daily AI budget spread over the orders captured in the range (GR-17).
     */
    Double target(KpiDef def, KpiRange range) {
        Optional<Double> pinned = configDouble(CONFIG_TARGET_PREFIX + def.key());
        if (pinned.isPresent()) return pinned.get();
        if (def.fixedTarget() != null) return def.fixedTarget();
        if (def.baselinePct() != null) return KpiMath.targetFromBaseline(configDouble(CONFIG_BASELINE_PREFIX + def.key()).orElse(null), def.baselinePct());
        return switch (def.key()) {
            case KpiCatalog.DECISION_ACCURACY -> publishGateAccuracy();
            case KpiCatalog.AI_COST_PER_ORDER -> aiBudgetPerOrder(range);
            default -> null;
        };
    }

    private Double publishGateAccuracy() {
        double sum = 0;
        int n = 0;
        for (DecisionDefinitionRepository.Row row : definitions.activeAll()) {
            DecisionDefinition d = row.definition();
            if ("live".equals(d.status()) && d.evaluation() != null && d.evaluation().minAccuracyInActBand() > 0) { sum += d.evaluation().minAccuracyInActBand(); n++; }
        }
        return n == 0 ? null : KpiMath.round(sum / n, 6);
    }

    private Double aiBudgetPerOrder(KpiRange range) {
        Optional<Double> budget = configDouble(ConfigKeys.AI_BUDGET_USD_PER_DAY);
        if (budget.isEmpty()) return null;
        double orders = repo.ordersCaptured(range).values().stream().mapToDouble(Double::doubleValue).sum();
        return orders <= 0 ? null : KpiMath.round(budget.get() * range.days() / orders, 6);
    }

    private Optional<Double> configDouble(String key) {
        return config.get(key).map(v -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString()));
    }

    private LocalDate today() { return KpiRange.dayOf(time.now()); }
}
