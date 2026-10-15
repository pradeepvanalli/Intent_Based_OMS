package ai.intentoms.analytics.kpi;

import ai.intentoms.analytics.kpi.KpiRepository.LabelledRecord;
import ai.intentoms.decision.studio.Calibration;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the daily points of every KPI straight from the source tables (FR-ANL-01). Shared by the live dashboard
 * ({@link KpiService}) and the nightly materialisation ({@link KpiDailyRollup}) so both agree to the decimal.
 */
@Component
public class KpiSeriesComputer {
    private final KpiRepository repo;
    private final TimeSource time;

    public KpiSeriesComputer(KpiRepository repo, TimeSource time) {
        this.repo = repo;
        this.time = time;
    }

    /** Daily points of {@code key} over {@code range}, computed live. Gauges yield today's value only (there is no history without snapshots). */
    public List<DayPoint> live(String key, KpiRange range) {
        return switch (key) {
            case KpiCatalog.TOUCHLESS_RATE, KpiCatalog.TOUCHLESS_TODAY -> repo.touchless(range);
            case KpiCatalog.PROMISE_ACCURACY -> repo.promiseAccuracy(range);
            case KpiCatalog.SPLIT_RATE -> repo.splitRate(range);
            case KpiCatalog.COST_TO_SERVE -> repo.costToServe(range);
            case KpiCatalog.MARGIN_FLOOR_COMPLIANCE -> repo.marginFloorCompliance(range);
            case KpiCatalog.STORE_REJECT_RATE -> DayPoint.merge(repo.storeRejects(range), repo.storeFulfilmentGroups(range));
            case KpiCatalog.EXCEPTION_RESOLUTION_MEDIAN -> repo.exceptionResolutionDaily(range);
            case KpiCatalog.RETURN_ABUSE_LOSS -> DayPoint.scaleNumerator(repo.returnAbuseLoss(range), 1000.0);
            case KpiCatalog.WISMO_PER_100 -> DayPoint.merge(scale(repo.wismoContacts(range), 100.0), repo.ordersPlaced(range));
            case KpiCatalog.POLICY_LEAD_TIME -> repo.policyLeadTimeDaily(range);
            case KpiCatalog.DECISION_ACCURACY -> accuracyPoints(repo.labelledRecords(range));
            case KpiCatalog.CALIBRATION_ERROR -> calibrationPoints(repo.labelledRecords(range));
            case KpiCatalog.OVERRIDE_RATE -> repo.overrides(range);
            case KpiCatalog.AI_COST_PER_ORDER -> DayPoint.merge(repo.aiSpend(range), repo.ordersCaptured(range));
            case KpiCatalog.OPEN_ORDERS -> gauge(range, repo.openOrders());
            case KpiCatalog.EXCEPTIONS_OPEN -> gauge(range, repo.exceptionsOpen());
            default -> throw new IllegalArgumentException("Unknown KPI " + key);
        };
    }

    public List<LabelledRecord> labelled(KpiRange range) { return repo.labelledRecords(range); }

    /** Per day: correct / labelled. */
    public static List<DayPoint> accuracyPoints(List<LabelledRecord> records) {
        Map<LocalDate, double[]> byDay = new TreeMap<>();
        for (LabelledRecord r : records) {
            double[] acc = byDay.computeIfAbsent(r.day(), d -> new double[2]);
            if (r.correct()) acc[0]++;
            acc[1]++;
        }
        List<DayPoint> out = new ArrayList<>();
        byDay.forEach((d, a) -> out.add(new DayPoint(d, a[0], a[1])));
        return out;
    }

    /** Per day: calibration error x labelled / labelled, so merged buckets are volume-weighted (GR-12 maths from the decision module). */
    public static List<DayPoint> calibrationPoints(List<LabelledRecord> records) {
        Map<LocalDate, List<Calibration.Observation>> byDay = new TreeMap<>();
        for (LabelledRecord r : records) byDay.computeIfAbsent(r.day(), d -> new ArrayList<>()).add(new Calibration.Observation(r.confidence(), r.correct()));
        List<DayPoint> out = new ArrayList<>();
        byDay.forEach((d, obs) -> out.add(new DayPoint(d, Calibration.compute(obs).error() * obs.size(), obs.size())));
        return out;
    }

    /** Exact calibration error over every labelled record (volume-weighted by construction). */
    public static double calibrationError(List<LabelledRecord> records) {
        if (records.isEmpty()) return 0.0;
        List<Calibration.Observation> obs = new ArrayList<>(records.size());
        for (LabelledRecord r : records) obs.add(new Calibration.Observation(r.confidence(), r.correct()));
        return Calibration.compute(obs).error();
    }

    private List<DayPoint> gauge(KpiRange range, long count) {
        LocalDate today = KpiRange.dayOf(time.now());
        return range.contains(today) ? List.of(new DayPoint(today, count, 1)) : List.of();
    }

    private static Map<LocalDate, Double> scale(Map<LocalDate, Double> m, double factor) {
        Map<LocalDate, Double> out = new TreeMap<>();
        m.forEach((d, v) -> out.put(d, v * factor));
        return out;
    }
}
