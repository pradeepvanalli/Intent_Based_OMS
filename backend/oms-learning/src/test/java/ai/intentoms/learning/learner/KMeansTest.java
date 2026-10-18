package ai.intentoms.learning.learner;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class KMeansTest {
    private static double[][] blobs(int perBlob, long seed) {
        Random r = new Random(seed);
        double[][] x = new double[perBlob * 2][];
        for (int i = 0; i < perBlob; i++) x[i] = new double[]{r.nextGaussian() * 0.2, r.nextGaussian() * 0.2};
        for (int i = 0; i < perBlob; i++) x[perBlob + i] = new double[]{5 + r.nextGaussian() * 0.2, 5 + r.nextGaussian() * 0.2};
        return x;
    }

    @Test void findsTwoWellSeparatedGroupsAndPicksKBySilhouette() {
        double[][] x = blobs(40, 3);
        KMeans.Result r = KMeans.best(x, 5, 42);
        assertThat(r.k()).isEqualTo(2);
        assertThat(r.silhouette()).isGreaterThan(0.8);
        int first = r.assignment()[0];
        for (int i = 0; i < 40; i++) assertThat(r.assignment()[i]).isEqualTo(first);
        for (int i = 40; i < 80; i++) assertThat(r.assignment()[i]).isNotEqualTo(first);
    }

    @Test void sameSeedSameSegments() {
        double[][] x = blobs(30, 9);
        assertThat(KMeans.fit(x, 3, 42).assignment()).containsExactly(KMeans.fit(x, 3, 42).assignment());
    }

    @Test void clustersBelowTheMinimumSizeAreFoldedIntoTheirNeighbour() {
        double[][] x = new double[45][];
        Random r = new Random(1);
        for (int i = 0; i < 40; i++) x[i] = new double[]{r.nextGaussian() * 0.1, 0};
        for (int i = 40; i < 45; i++) x[i] = new double[]{10 + r.nextGaussian() * 0.1, 0};
        KMeans.Result raw = KMeans.fit(x, 2, 42);
        assertThat(raw.k()).isEqualTo(2);
        KMeans.Result folded = KMeans.dropSmall(x, raw, 30);
        assertThat(folded.k()).isEqualTo(1);
        assertThat(folded.size(0)).isEqualTo(45);
    }

    @Test void templateNamesDescribeWhatSetsASegmentApart() {
        var fast = new SegmentNamer.Profile(0, 40, Map.of("split_tolerance", 0.7, "speed_vs_cost", 0.9, "pickup_share", 0.0, "orders", 2.0));
        var cheap = new SegmentNamer.Profile(1, 40, Map.of("split_tolerance", 0.3, "speed_vs_cost", -0.9, "pickup_share", 0.0, "orders", 2.0));
        Map<String, Double> overall = Map.of("split_tolerance", 0.5, "speed_vs_cost", 0.0, "pickup_share", 0.0, "orders", 2.0);
        Map<String, Double> spread = Map.of("split_tolerance", 0.3, "speed_vs_cost", 0.9, "pickup_share", 0.1, "orders", 1.0);
        var names = SegmentNamer.template(java.util.List.of(fast, cheap), overall, spread);
        assertThat(names.get(0).label()).isEqualTo("Speed-first, split-tolerant customers");
        assertThat(names.get(1).label()).isEqualTo("Cost-conscious, one-delivery customers");
        assertThat(names.get(0).description()).startsWith("40 customers.");
    }
}
