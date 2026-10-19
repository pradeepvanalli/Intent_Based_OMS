package ai.intentoms.sourcing.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FS-04 replay: re-scores the plans every recorded order actually considered under a proposed weight vector and asks
 * which plan of the order's chosen policy would have won. Orders whose choice changes are scored with a reward model
 * fitted on the chosen plans (reward ~ cost + time + soft + split components, ridge least squares), so the estimate is
 * model-based: the alternative plans were never shipped. The result is the proposal's evaluation report
 * ({@code method: plan_rescoring_replay}) with a 95% interval over the per-order predicted change.
 */
public final class SourcingWeightReplay {
    private SourcingWeightReplay() {}

    public static final List<String> COMPONENTS = List.of("cost", "time", "soft", "split");

    /** One recorded order: its considered plans (policy, components), which one was chosen, and the chosen plan's reward. */
    public record Order(String orderId, List<Plan> plans, Double reward) {}

    public record Plan(String id, String policy, Map<String, Double> components, boolean chosen) {}

    public static Map<String, Object> replay(List<Order> orders, Map<String, Double> current, Map<String, Double> proposed) {
        List<double[]> train = new ArrayList<>();
        for (Order o : orders) {
            if (o.reward() == null) continue;
            Plan chosen = o.plans().stream().filter(Plan::chosen).findFirst().orElse(null);
            if (chosen == null || !complete(chosen)) continue;
            train.add(new double[]{chosen.components().get("cost"), chosen.components().get("time"), chosen.components().get("soft"), chosen.components().get("split"), o.reward()});
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", "plan_rescoring_replay");
        if (train.size() < 20) {
            r.put("sampleSize", train.size());
            r.put("reason", "only " + train.size() + " rewarded orders with recorded plans (need 20)");
            return r;
        }
        double[] beta = fit(train, 1e-3);
        List<Double> deltas = new ArrayList<>();
        int changed = 0;
        double baseline = 0;
        for (Order o : orders) {
            Plan chosen = o.plans().stream().filter(Plan::chosen).findFirst().orElse(null);
            if (chosen == null || !complete(chosen)) continue;
            if (o.reward() != null) baseline += o.reward();
            Plan best = chosen;
            double bestScore = score(chosen, proposed);
            for (Plan p : o.plans()) {
                if (p == chosen || !p.policy().equals(chosen.policy()) || !complete(p)) continue;
                double s = score(p, proposed);
                if (s > bestScore + 1e-9) { best = p; bestScore = s; }
            }
            double delta = best == chosen ? 0.0 : predict(beta, best) - predict(beta, chosen);
            if (best != chosen) changed++;
            deltas.add(delta);
        }
        int n = deltas.size();
        double mean = deltas.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = deltas.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / Math.max(1, n - 1);
        double se = Math.sqrt(var / Math.max(1, n));
        double base = baseline / Math.max(1, train.size());
        r.put("sampleSize", n);
        r.put("decisionsChanged", changed);
        r.put("baselineReward", round(base));
        r.put("liftEstimate", round(mean));
        r.put("proposedRewardEstimate", round(base + mean));
        r.put("confidenceInterval", Map.of("lower", round(mean - 1.96 * se), "upper", round(mean + 1.96 * se)));
        Map<String, Object> model = new LinkedHashMap<>();
        for (int i = 0; i < COMPONENTS.size(); i++) model.put(COMPONENTS.get(i), round(beta[i + 1]));
        model.put("intercept", round(beta[0]));
        model.put("trainingOrders", train.size());
        r.put("rewardModel", model);
        r.put("weights", Map.of("current", current, "proposed", proposed));
        r.put("caveat", "model-based: plans not chosen were never shipped; confirm with a shadow run before promoting");
        return r;
    }

    static boolean complete(Plan p) { return p.components() != null && p.components().keySet().containsAll(COMPONENTS); }

    static double score(Plan p, Map<String, Double> w) {
        double s = 0;
        for (String k : COMPONENTS) s += w.getOrDefault(k, 0.0) * p.components().get(k);
        return s;
    }

    static double predict(double[] beta, Plan p) {
        double y = beta[0];
        for (int i = 0; i < COMPONENTS.size(); i++) y += beta[i + 1] * p.components().get(COMPONENTS.get(i));
        return y;
    }

    /** Ridge least squares for y ~ 1 + x1..x4 (rows are {x1, x2, x3, x4, y}); the intercept is not penalised. */
    static double[] fit(List<double[]> rows, double lambda) {
        int k = 5;
        double[][] a = new double[k][k + 1];
        for (double[] r : rows) {
            double[] x = {1, r[0], r[1], r[2], r[3]};
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) a[i][j] += x[i] * x[j];
                a[i][k] += x[i] * r[4];
            }
        }
        for (int i = 1; i < k; i++) a[i][i] += lambda * rows.size();
        // Gaussian elimination with partial pivoting
        for (int c = 0; c < k; c++) {
            int piv = c;
            for (int r = c + 1; r < k; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            double[] tmp = a[c]; a[c] = a[piv]; a[piv] = tmp;
            if (Math.abs(a[c][c]) < 1e-12) continue;
            for (int r = 0; r < k; r++) {
                if (r == c) continue;
                double f = a[r][c] / a[c][c];
                for (int j = c; j <= k; j++) a[r][j] -= f * a[c][j];
            }
        }
        double[] beta = new double[k];
        for (int i = 0; i < k; i++) beta[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : a[i][k] / a[i][i];
        return beta;
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000d; }
}
