package ai.intentoms.learning.experiment;

import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.store.ExperimentRepository;
import ai.intentoms.learning.store.LearningBaselineRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * XP-01: cumulative lift of everything the learning layer changed, measured against the persistent holdout. For the
 * reward and each reward term, the mean over episodes whose decision ran for a holdout customer is compared with the
 * mean over everyone else, with a 95% (Welch) confidence interval on the difference. {@code betterWhen} says which
 * direction is good, since a lower cost, split, fraud or contact term is an improvement.
 */
@Service
public class LiftService {
    private static final Set<String> LOWER_IS_BETTER = Set.of("cost", "split", "fraud", "cx");
    private static final List<String> TERMS = List.of("fill", "otd", "cost", "split", "fraud", "cx", "inv");

    private final ExperimentRepository experiments;
    private final ExperimentMetricsRepository metrics;
    private final LearningBaselineRepository baselines;
    private final int minPerArm;

    public LiftService(ExperimentRepository experiments, ExperimentMetricsRepository metrics, LearningBaselineRepository baselines,
                       @org.springframework.beans.factory.annotation.Value("${oms.learning.lift-min-per-arm:30}") int minPerArm) {
        this.experiments = experiments;
        this.metrics = metrics;
        this.baselines = baselines;
        this.minPerArm = Math.max(2, minPerArm);
    }

    public Map<String, Object> lift(int days) {
        Experiment holdout = experiments.runningOn(ExperimentManager.HOLDOUT_TARGET, ExperimentMode.HOLDOUT).orElse(null);
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, days)));
        if (holdout != null && holdout.startAt().isAfter(since)) since = holdout.startAt();
        List<ExperimentMetricsRepository.LiftRow> rows = metrics.liftRows(since);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", days);
        out.put("since", since.toString());
        if (holdout == null) {
            out.put("holdout", null);
            out.put("note", "No persistent holdout is running; start one (POST /api/v1/learning/experiments/holdout) to measure cumulative lift");
        } else {
            out.put("holdout", Map.of("experimentId", holdout.experimentId(), "trafficShare", holdout.trafficShare(), "startAt", holdout.startAt().toString()));
        }
        out.put("baselines", baselines.all());
        List<ExperimentMetricsRepository.LiftRow> held = rows.stream().filter(r -> "holdout".equals(r.arm())).toList();
        List<ExperimentMetricsRepository.LiftRow> treated = rows.stream().filter(r -> !"holdout".equals(r.arm())).toList();
        out.put("headline", perfectOrderRate(rows, minPerArm));
        List<Map<String, Object>> metricsOut = new ArrayList<>();
        metricsOut.add(metric("reward", treated, held, ExperimentMetricsRepository.LiftRow::reward, minPerArm));
        for (String term : TERMS) metricsOut.add(metric(term, treated, held, r -> r.components().get(term), minPerArm));
        out.put("metrics", metricsOut);
        out.put("minPerArm", minPerArm);
        Map<String, Object> byDecision = new LinkedHashMap<>();
        rows.stream().map(ExperimentMetricsRepository.LiftRow::decision).distinct().sorted().forEach(d -> byDecision.put(d,
            metric("reward", treated.stream().filter(r -> d.equals(r.decision())).toList(), held.stream().filter(r -> d.equals(r.decision())).toList(),
                ExperimentMetricsRepository.LiftRow::reward, minPerArm)));
        out.put("byDecision", byDecision);
        return out;
    }

    /**
     * One measure, treated versus holdout. A difference is called significant only with at least {@code minPerArm}
     * episodes on each side (default 30) and some variation in the values on each side (defect 29: four identical holdout
     * values once produced a "significant" result, because a side with no spread makes the interval far too narrow);
     * otherwise the means are shown with {@code enoughData: false} and a reason.
     */
    static Map<String, Object> metric(String name, List<ExperimentMetricsRepository.LiftRow> treated, List<ExperimentMetricsRepository.LiftRow> held,
                                      Function<ExperimentMetricsRepository.LiftRow, Double> value, int minPerArm) {
        double[] t = treated.stream().map(value).filter(v -> v != null).mapToDouble(Double::doubleValue).toArray();
        double[] h = held.stream().map(value).filter(v -> v != null).mapToDouble(Double::doubleValue).toArray();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", name);
        m.put("betterWhen", LOWER_IS_BETTER.contains(name) ? "lower" : "higher");
        m.put("nTreated", t.length);
        m.put("nHoldout", h.length);
        if (t.length < 2 || h.length < 2) {
            m.put("lift", null);
            m.put("significant", false);
            m.put("enoughData", false);
            m.put("reason", "need at least " + minPerArm + " episodes with a reward on each side (" + t.length + " with learning, " + h.length + " in the holdout)");
            return m;
        }
        double mt = mean(t), mh = mean(h);
        double vt = var(t, mt), vh = var(h, mh);
        double se = Math.sqrt(vt / t.length + vh / h.length);
        double diff = mt - mh;
        m.put("treatedMean", round(mt));
        m.put("holdoutMean", round(mh));
        m.put("lift", round(diff));
        m.put("ciLower", round(diff - 1.96 * se));
        m.put("ciUpper", round(diff + 1.96 * se));
        boolean enough = t.length >= minPerArm && h.length >= minPerArm;
        boolean varies = vt > 0 && vh > 0;
        m.put("enoughData", enough && varies);
        if (!enough) m.put("reason", "need at least " + minPerArm + " episodes with a reward on each side (" + t.length + " with learning, " + h.length + " in the holdout)");
        else if (!varies) m.put("reason", "every value " + (vt > 0 ? "in the holdout" : vh > 0 ? "with learning" : "on both sides") + " is the same, so the difference cannot be judged yet");
        boolean excludesZero = enough && varies && (diff - 1.96 * se > 0 || diff + 1.96 * se < 0);
        m.put("significant", excludesZero);
        m.put("improvement", excludesZero && (LOWER_IS_BETTER.contains(name) ? diff < 0 : diff > 0));
        return m;
    }

    /** The north-star reward term (§12 AC-2) on order episodes: 1 for a perfect order, else 0. */
    public static final String PERFECT = "perfect";

    /**
     * §12 AC-2: the perfect-order rate, the share of orders whose order episode carries {@code perfect = 1}, with
     * learning versus in the holdout and a 95% interval on the difference. One value per order (its latest order episode).
     */
    static Map<String, Object> perfectOrderRate(List<ExperimentMetricsRepository.LiftRow> rows, int minPerArm) {
        Map<String, ExperimentMetricsRepository.LiftRow> perOrder = new LinkedHashMap<>();
        int i = 0;
        for (ExperimentMetricsRepository.LiftRow r : rows) {
            if (r.components() == null || r.components().get(PERFECT) == null) continue;
            String key = r.orderId() != null ? r.orderId() : "row-" + i++;
            ExperimentMetricsRepository.LiftRow prev = perOrder.get(key);
            if (prev == null || (r.createdAt() != null && prev.createdAt() != null && r.createdAt().isAfter(prev.createdAt()))) perOrder.put(key, r);
        }
        List<ExperimentMetricsRepository.LiftRow> orders = new ArrayList<>(perOrder.values());
        Map<String, Object> m = metric("perfect_order_rate", orders.stream().filter(r -> !"holdout".equals(r.arm())).toList(),
            orders.stream().filter(r -> "holdout".equals(r.arm())).toList(), r -> r.components().get(PERFECT) >= 1 ? 1.0 : 0.0, minPerArm);
        m.put("label", "Perfect-order rate");
        m.put("definition", "share of orders delivered in full and on time with nothing going wrong afterwards");
        m.put("unit", "share");
        if (orders.isEmpty()) m.put("reason", "no order has a perfect-order outcome yet; it is recorded when an order's episode matures");
        return m;
    }

    private static double mean(double[] xs) { double s = 0; for (double x : xs) s += x; return s / xs.length; }
    private static double var(double[] xs, double m) { double s = 0; for (double x : xs) s += (x - m) * (x - m); return s / (xs.length - 1); }
    private static double round(double v) { return Math.round(v * 10_000d) / 10_000d; }
}
