package ai.intentoms.learning.learner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Pure logic of the fulfilment and promise learners (FS-01, FS-05, FS-06, CX-02, CX-03, CX-04). */
class FulfilmentLearnersUnitTest {

    @Test void backlogCurveIsMonotoneAndInterpolates() {
        List<double[]> samples = new ArrayList<>();
        for (int i = 0; i < 40; i++) samples.add(new double[]{10, i % 10 == 0 ? 1 : 0});   // 10% breach at low backlog
        for (int i = 0; i < 40; i++) samples.add(new double[]{100, i % 2 == 0 ? 1 : 0});   // 50% breach at high backlog
        for (int i = 0; i < 40; i++) samples.add(new double[]{50, i % 4 == 0 ? 1 : 0});    // 25% in between
        var curve = BacklogBreachCurve.fit(samples, 3, 10);
        assertThat(curve).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 1; i < curve.size(); i++) assertThat(curve.get(i).breach()).isGreaterThanOrEqualTo(curve.get(i - 1).breach());
        double low = BacklogBreachCurve.at(curve, 0).orElseThrow();
        double high = BacklogBreachCurve.at(curve, 500).orElseThrow();
        assertThat(low).isLessThan(0.2);
        assertThat(high).isGreaterThan(0.4);
        var roundTrip = BacklogBreachCurve.encode(curve);
        assertThat(roundTrip).allMatch(s -> s.matches("\\d+:0\\.\\d{4}"));
    }

    @Test void banditWeightsFavourTheBetterArmAfterPropensityCorrection() {
        List<BanditRewardLearner.Logged> logged = new ArrayList<>();
        // The default arm is applied most of the time (p = 0.93) and earns 0.5; the explored arm (p = 0.033) earns 0.9.
        for (int i = 0; i < 60; i++) logged.add(new BanditRewardLearner.Logged("b", "ship_complete", 0.9333, 0.5));
        for (int i = 0; i < 8; i++) logged.add(new BanditRewardLearner.Logged("b", "split_up_to_k", 0.0333, 0.9));
        for (int i = 0; i < 2; i++) logged.add(new BanditRewardLearner.Logged("b", "nearest_node", 0.0333, 0.1)); // too few: gets the mean
        Map<String, Double> w = BanditRewardLearner.weights(logged, 5, 0.2);
        assertThat(w.get("split_up_to_k")).isGreaterThan(w.get("ship_complete"));
        assertThat(w.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(0.001));
        assertThat(w).containsKey("nearest_node");
        assertThat(BanditRewardLearner.encode(w)).allMatch(s -> s.contains(":"));
        assertThat(SourcingPolicyBandit.parseWeights(BanditRewardLearner.encode(w))).containsOnlyKeys(w.keySet());
    }

    @Test void banditBucketsByChannelIntentBasketAndRegion() {
        assertThat(SourcingPolicyBandit.bucket(Map.of("channel", "web", "intentPolicy", "ship_complete", "basketUnits", 3, "destinationZip", "10001")))
            .isEqualTo("web|ship_complete|2-3|z1");
        assertThat(SourcingPolicyBandit.bucket(Map.of("channel", "pos", "lineCount", 6, "destinationState", "TX"))).isEqualTo("pos|none|4+|TX");
        assertThat(SourcingPolicyBandit.bucket(Map.of())).isEqualTo("any|none|1|any");
    }

    @Test void rejectPenaltyScalesWithTheNodesRejectRateRelativeToTheFleet() {
        assertThat(NodeReliabilityLearner.rejectPenalty(0.3, 0.10, 0.05)).isCloseTo(0.6, within(1e-9));
        assertThat(NodeReliabilityLearner.rejectPenalty(0.3, 0.01, 0.05)).isEqualTo(0.06);
        assertThat(NodeReliabilityLearner.rejectPenalty(0.3, 0.50, 0.05)).isEqualTo(0.9);
        assertThat(NodeReliabilityLearner.subject("n1", "apparel", "morning")).isEqualTo("n1|apparel|morning");
        assertThat(NodeReliabilityLearner.subject("n1", null, "morning")).isEqualTo("n1");
    }

    @Test void promiseBufferMaximisesConversionTimesOnTime() {
        var rows = List.of(
            new PromiseConversionRepository.BufferStats("default", 0, 100, 60, 60, 40),   // converts best, misses a third
            new PromiseConversionRepository.BufferStats("default", 1, 100, 55, 55, 52),   // slightly fewer buy, almost all on time
            new PromiseConversionRepository.BufferStats("default", 3, 100, 30, 30, 30),   // too padded
            new PromiseConversionRepository.BufferStats("default", 2, 5, 5, 5, 5));       // too few impressions to count
        assertThat(PromiseBufferLearner.best(rows, 20).bufferDays()).isEqualTo(1);
        assertThat(PromiseBufferLearner.best(List.of(rows.get(3)), 20)).isNull();
        assertThat(PromiseBufferLearner.segment("web")).isEqualTo("channel:web");
        assertThat(PromiseBufferLearner.segment(null)).isEqualTo("default");
    }

    @Test void warnEarlyThresholdBalancesCaughtDelaysAgainstFalseAlarms() {
        List<WarnEarlyThresholdLearner.Observed> obs = new ArrayList<>();
        for (int i = 0; i < 10; i++) obs.add(new WarnEarlyThresholdLearner.Observed(3, true));   // late orders reach 3
        for (int i = 0; i < 5; i++) obs.add(new WarnEarlyThresholdLearner.Observed(2, true));    // some late orders only reach 2
        for (int i = 0; i < 30; i++) obs.add(new WarnEarlyThresholdLearner.Observed(1, false));  // most on-time orders sit at 1
        for (int i = 0; i < 4; i++) obs.add(new WarnEarlyThresholdLearner.Observed(2, false));
        assertThat(WarnEarlyThresholdLearner.bestThreshold(obs, 1.0, 0.3)).isEqualTo(2);
        assertThat(WarnEarlyThresholdLearner.bestThreshold(List.of(new WarnEarlyThresholdLearner.Observed(3, false)), 1.0, 0.3)).isNull();
    }

    @Test void appeasementPicksTheSmallestCreditThatStopsRepeatComplaints() {
        List<AppeasementLevelLearner.Case> cases = new ArrayList<>();
        for (int i = 0; i < 20; i++) cases.add(new AppeasementLevelLearner.Case("default", 0, i < 10));    // 50% complain again
        for (int i = 0; i < 20; i++) cases.add(new AppeasementLevelLearner.Case("default", 10, i < 2));    // 10%
        for (int i = 0; i < 20; i++) cases.add(new AppeasementLevelLearner.Case("default", 20, i < 2));    // 10%, costs more
        for (int i = 0; i < 20; i++) cases.add(new AppeasementLevelLearner.Case("default", 40, i < 0));    // over budget
        assertThat(AppeasementLevelLearner.bestLevel(cases, 10, 25, 0.02)).isEqualTo(10.0);
        assertThat(AppeasementLevelLearner.segment("Gold")).isEqualTo("tier:gold");
    }
}
