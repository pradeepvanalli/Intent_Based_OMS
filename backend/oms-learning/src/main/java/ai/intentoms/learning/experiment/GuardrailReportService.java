package ai.intentoms.learning.experiment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * §12 AC-3: over the last {@code weeks} (default 4), is any guardrail worse with learning than without it? Compares
 * traffic with learning ("treated") against the persistent holdout when the holdout has enough decisions, else against
 * all traffic in the {@code weeks} before the window ("baseline"). Per metric:
 * <ul>
 *   <li>override rate: worse when at least 2 points higher and the difference is significant (two-proportion z, 95%);</li>
 *   <li>decision latency p95: worse when above 300 ms and more than 25% higher;</li>
 *   <li>customer-contact rate (episodes with a contact term): worse when 2 points higher and significant;</li>
 *   <li>fraud false-positive rate (labelled orders flagged but confirmed good): worse when 2 points higher and significant;</li>
 *   <li>perfect-order rate: worse when 2 points lower and significant.</li>
 * </ul>
 * The same thresholds as the per-promotion guardrail monitor (EX-05). A side with fewer than
 * {@code oms.learning.guardrail-min-sample} (default 30) cases makes that metric "insufficient". Seeded rows never count.
 */
@Service
public class GuardrailReportService {
    static final double POINTS = 0.02;
    static final double MAX_P95_MS = 300;
    static final double P95_GROWTH = 1.25;

    private final ExperimentMetricsRepository metrics;
    @Value("${oms.learning.guardrail-min-sample:30}") private int minSample = 30;

    public GuardrailReportService(ExperimentMetricsRepository metrics) { this.metrics = metrics; }

    public Map<String, Object> report(int weeks) {
        int w = Math.max(1, Math.min(52, weeks));
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(7L * w));
        ExperimentMetricsRepository.ArmGuardrails treated = metrics.armGuardrails("treated", from, to);
        ExperimentMetricsRepository.ArmGuardrails holdout = metrics.armGuardrails("holdout", from, to);
        boolean useHoldout = holdout.decisions() >= minSample;
        ExperimentMetricsRepository.ArmGuardrails comparison = useHoldout ? holdout : metrics.armGuardrails(null, from.minus(Duration.ofDays(7L * w)), from);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weeks", w);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("comparedWith", useHoldout ? "holdout" : "baseline");
        out.put("comparison", useHoldout
            ? "customers in the persistent holdout over the same weeks"
            : "all traffic in the " + w + " weeks before (the holdout has " + holdout.decisions() + " decisions, fewer than " + minSample + ")");
        out.put("minSample", minSample);
        List<Map<String, Object>> rows = compare(treated, comparison, minSample);
        out.put("metrics", rows);
        boolean anyWorse = rows.stream().anyMatch(r -> "worse".equals(r.get("verdict")));
        boolean allJudged = rows.stream().noneMatch(r -> "insufficient".equals(r.get("verdict")));
        out.put("verdict", anyWorse ? "worse" : allJudged ? "not_worse" : "insufficient");
        out.put("summary", anyWorse
            ? "At least one guardrail is worse with learning: " + String.join(", ", rows.stream().filter(r -> "worse".equals(r.get("verdict"))).map(r -> String.valueOf(r.get("label"))).toList())
            : allJudged ? "No guardrail is worse with learning over the last " + w + " weeks"
            : "No guardrail is worse so far, but some have too little data to judge");
        List<Map<String, Object>> weekly = new ArrayList<>();
        for (int i = w; i >= 1; i--) {
            Instant wf = to.minus(Duration.ofDays(7L * i)), wt = to.minus(Duration.ofDays(7L * (i - 1)));
            ExperimentMetricsRepository.ArmGuardrails t = metrics.armGuardrails("treated", wf, wt);
            ExperimentMetricsRepository.ArmGuardrails c = useHoldout ? metrics.armGuardrails("holdout", wf, wt) : comparison;
            Map<String, Object> wk = new LinkedHashMap<>();
            wk.put("from", wf.toString());
            wk.put("to", wt.toString());
            wk.put("treated", values(t));
            wk.put("comparison", values(c));
            weekly.add(wk);
        }
        out.put("weekly", weekly);
        return out;
    }

    static List<Map<String, Object>> compare(ExperimentMetricsRepository.ArmGuardrails t, ExperimentMetricsRepository.ArmGuardrails c, int minSample) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(rate("override_rate", "Override rate", "lower", t.overridden(), t.decisions(), c.overridden(), c.decisions(), minSample));
        rows.add(latency(t, c, minSample));
        rows.add(rate("contact_rate", "Customer contact rate", "lower", t.contacted(), t.episodes(), c.contacted(), c.episodes(), minSample));
        rows.add(rate("fraud_false_positive_rate", "Fraud false-positive rate", "lower", t.falsePositives(), t.fraudLabelled(), c.falsePositives(), c.fraudLabelled(), minSample));
        rows.add(rate("perfect_order_rate", "Perfect-order rate", "higher", t.perfect(), t.perfectKnown(), c.perfect(), c.perfectKnown(), minSample));
        return rows;
    }

    private static Map<String, Object> rate(String metric, String label, String betterWhen, long tHits, long tN, long cHits, long cN, int minSample) {
        Map<String, Object> m = base(metric, label, betterWhen, tN, cN);
        Double tr = tN == 0 ? null : (double) tHits / tN, cr = cN == 0 ? null : (double) cHits / cN;
        m.put("treated", tr == null ? null : round(tr));
        m.put("comparison", cr == null ? null : round(cr));
        if (tN < minSample || cN < minSample) return insufficient(m, tN, cN, minSample);
        double diff = tr - cr;
        double pooled = (double) (tHits + cHits) / (tN + cN);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / tN + 1.0 / cN));
        boolean significant = se > 0 && Math.abs(diff) / se > 1.96;
        double bad = "lower".equals(betterWhen) ? diff : -diff;
        m.put("difference", round(diff));
        m.put("significant", significant);
        boolean worse = bad >= POINTS && significant;
        m.put("verdict", worse ? "worse" : "not_worse");
        m.put("reason", String.format(Locale.ROOT, "%.1f%% with learning vs %.1f%% (%s%.1f points%s)", tr * 100, cr * 100, diff >= 0 ? "+" : "", diff * 100,
            significant ? ", significant" : ", within noise"));
        return m;
    }

    private static Map<String, Object> latency(ExperimentMetricsRepository.ArmGuardrails t, ExperimentMetricsRepository.ArmGuardrails c, int minSample) {
        Map<String, Object> m = base("latency_p95_ms", "Decision latency (p95)", "lower", t.decisions(), c.decisions());
        m.put("treated", t.decisions() == 0 ? null : round(t.latencyP95()));
        m.put("comparison", c.decisions() == 0 ? null : round(c.latencyP95()));
        if (t.decisions() < minSample || c.decisions() < minSample) return insufficient(m, t.decisions(), c.decisions(), minSample);
        boolean worse = t.latencyP95() > MAX_P95_MS && t.latencyP95() > c.latencyP95() * P95_GROWTH;
        m.put("difference", round(t.latencyP95() - c.latencyP95()));
        m.put("verdict", worse ? "worse" : "not_worse");
        m.put("reason", String.format(Locale.ROOT, "%.0f ms with learning vs %.0f ms", t.latencyP95(), c.latencyP95()));
        return m;
    }

    private static Map<String, Object> base(String metric, String label, String betterWhen, long tN, long cN) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("label", label);
        m.put("betterWhen", betterWhen);
        m.put("nTreated", tN);
        m.put("nComparison", cN);
        return m;
    }

    private static Map<String, Object> insufficient(Map<String, Object> m, long tN, long cN, int minSample) {
        m.put("verdict", "insufficient");
        m.put("reason", "need at least " + minSample + " cases on each side (" + tN + " with learning, " + cN + " to compare with)");
        return m;
    }

    private static Map<String, Object> values(ExperimentMetricsRepository.ArmGuardrails a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisions", a.decisions());
        m.put("overrideRate", a.decisions() == 0 ? null : round((double) a.overridden() / a.decisions()));
        m.put("latencyP95Ms", a.decisions() == 0 ? null : round(a.latencyP95()));
        m.put("contactRate", a.episodes() == 0 ? null : round((double) a.contacted() / a.episodes()));
        m.put("fraudFalsePositiveRate", a.fraudLabelled() == 0 ? null : round((double) a.falsePositives() / a.fraudLabelled()));
        m.put("perfectOrderRate", a.perfectKnown() == 0 ? null : round((double) a.perfect() / a.perfectKnown()));
        return m;
    }

    private static double round(double v) { return Math.round(v * 10_000d) / 10_000d; }
}
