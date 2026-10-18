package ai.intentoms.learning.learner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Plain-Java k-means for CI-02 (no ML library on this stack): k-means++ seeding from a fixed seed so a rerun on the same
 * data gives the same segments, Lloyd iterations until assignments stop changing (max 100), and a silhouette score to
 * pick k. Inputs are expected to be standardised already.
 */
public final class KMeans {
    private KMeans() {}

    public record Result(int k, double[][] centroids, int[] assignment, double silhouette) {
        public int size(int cluster) {
            int n = 0;
            for (int a : assignment) if (a == cluster) n++;
            return n;
        }
    }

    public static Result fit(double[][] x, int k, long seed) {
        int n = x.length;
        Random rng = new Random(seed);
        double[][] c = seedPlusPlus(x, k, rng);
        int[] assign = new int[n];
        Arrays.fill(assign, -1);
        for (int iter = 0; iter < 100; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = nearest(x[i], c);
                if (best != assign[i]) { assign[i] = best; changed = true; }
            }
            c = centroids(x, assign, k, c);
            if (!changed) break;
        }
        return new Result(k, c, assign, silhouette(x, assign, k, 2000, seed));
    }

    /** Fits k = 2..maxK and keeps the best silhouette (ties go to the smaller k). */
    public static Result best(double[][] x, int maxK, long seed) {
        Result best = null;
        for (int k = 2; k <= Math.max(2, maxK); k++) {
            if (k > x.length) break;
            Result r = fit(x, k, seed);
            if (best == null || r.silhouette() > best.silhouette() + 1e-9) best = r;
        }
        return best;
    }

    /** Moves members of clusters smaller than {@code minSize} to their nearest remaining cluster and renumbers 0..k'-1. */
    public static Result dropSmall(double[][] x, Result r, int minSize) {
        List<Integer> keep = new ArrayList<>();
        for (int j = 0; j < r.k(); j++) if (r.size(j) >= minSize) keep.add(j);
        if (keep.isEmpty() || keep.size() == r.k()) return r;
        double[][] kept = new double[keep.size()][];
        for (int i = 0; i < keep.size(); i++) kept[i] = r.centroids()[keep.get(i)];
        int[] assign = new int[x.length];
        for (int i = 0; i < x.length; i++) {
            int old = r.assignment()[i];
            int idx = keep.indexOf(old);
            assign[i] = idx >= 0 ? idx : nearest(x[i], kept);
        }
        double[][] c = centroids(x, assign, keep.size(), kept);
        return new Result(keep.size(), c, assign, keep.size() < 2 ? 0 : silhouette(x, assign, keep.size(), 2000, 1L));
    }

    static double[][] seedPlusPlus(double[][] x, int k, Random rng) {
        int n = x.length;
        double[][] c = new double[k][];
        c[0] = x[rng.nextInt(n)].clone();
        double[] d2 = new double[n];
        for (int j = 1; j < k; j++) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                double best = Double.MAX_VALUE;
                for (int m = 0; m < j; m++) best = Math.min(best, dist2(x[i], c[m]));
                d2[i] = best;
                total += best;
            }
            if (total <= 0) { c[j] = x[rng.nextInt(n)].clone(); continue; }
            double pick = rng.nextDouble() * total;
            int chosen = n - 1;
            for (int i = 0; i < n; i++) { pick -= d2[i]; if (pick <= 0) { chosen = i; break; } }
            c[j] = x[chosen].clone();
        }
        return c;
    }

    static double[][] centroids(double[][] x, int[] assign, int k, double[][] previous) {
        int d = x[0].length;
        double[][] sum = new double[k][d];
        int[] count = new int[k];
        for (int i = 0; i < x.length; i++) {
            count[assign[i]]++;
            for (int f = 0; f < d; f++) sum[assign[i]][f] += x[i][f];
        }
        double[][] c = new double[k][d];
        for (int j = 0; j < k; j++) {
            if (count[j] == 0) { c[j] = previous[j].clone(); continue; }
            for (int f = 0; f < d; f++) c[j][f] = sum[j][f] / count[j];
        }
        return c;
    }

    static int nearest(double[] p, double[][] c) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int j = 0; j < c.length; j++) {
            double d = dist2(p, c[j]);
            if (d < bd) { bd = d; best = j; }
        }
        return best;
    }

    static double dist2(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; s += d * d; }
        return s;
    }

    /** Mean silhouette over at most {@code sample} points (deterministic sample). */
    static double silhouette(double[][] x, int[] assign, int k, int sample, long seed) {
        int n = x.length;
        if (k < 2 || n < 3) return 0;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        if (n > sample) {
            Random rng = new Random(seed);
            for (int i = n - 1; i > 0; i--) { int j = rng.nextInt(i + 1); int t = idx[i]; idx[i] = idx[j]; idx[j] = t; }
        }
        int m = Math.min(n, sample);
        double total = 0;
        int counted = 0;
        for (int s = 0; s < m; s++) {
            int i = idx[s];
            double[] sum = new double[k];
            int[] cnt = new int[k];
            for (int t = 0; t < m; t++) {
                int j = idx[t];
                if (j == i) continue;
                sum[assign[j]] += Math.sqrt(dist2(x[i], x[j]));
                cnt[assign[j]]++;
            }
            int own = assign[i];
            if (cnt[own] == 0) continue;
            double a = sum[own] / cnt[own];
            double b = Double.MAX_VALUE;
            for (int c = 0; c < k; c++) if (c != own && cnt[c] > 0) b = Math.min(b, sum[c] / cnt[c]);
            if (b == Double.MAX_VALUE) continue;
            total += (b - a) / Math.max(a, b);
            counted++;
        }
        return counted == 0 ? 0 : total / counted;
    }
}
