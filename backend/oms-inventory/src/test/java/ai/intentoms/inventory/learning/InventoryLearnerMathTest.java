package ai.intentoms.inventory.learning;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Pure parts of the inventory learners (IU-01, IU-02, IU-04, IU-05, IU-06). */
class InventoryLearnerMathTest {
    @Test void inverseNormalMatchesKnownQuantiles() {
        assertThat(SafetyStockLearner.inverseNormal(0.95)).isCloseTo(1.6449, within(1e-4));
        assertThat(SafetyStockLearner.inverseNormal(0.99)).isCloseTo(2.3263, within(1e-4));
        assertThat(SafetyStockLearner.inverseNormal(0.5)).isCloseTo(0.0, within(1e-9));
    }

    @Test void dailyDemandStatisticsCountQuietDaysAsZero() {
        double[] s = SafetyStockLearner.meanStdev(List.of(10, 10), 4);   // 10, 10, 0, 0
        assertThat(s[0]).isEqualTo(5.0);
        assertThat(s[1]).isEqualTo(5.0);
    }

    @Test void ttlIsThe95thPercentilePlusTenPercentWithinBounds() {
        List<Double> minutes = new ArrayList<>();
        for (int i = 1; i <= 25; i++) minutes.add((double) i);
        assertThat(ReservationTtlLearner.ttlFor(minutes)).isEqualTo(26.0);
        assertThat(ReservationTtlLearner.ttlFor(List.of(0.1, 0.2))).isEqualTo(5.0);
        assertThat(ReservationTtlLearner.ttlFor(List.of(5000.0))).isEqualTo(1440.0);
    }

    @Test void transferPlanMatchesLargestSurplusToLargestNeed() {
        Map<String, Integer> onHand = Map.of("A", 100, "B", 1, "C", 0, "D", 40);
        Map<String, Double> weekly = Map.of("s|B", 10.0, "s|C", 5.0, "s|D", 2.0);
        List<TransferRecommender.Transfer> plan = TransferRecommender.plan("s", onHand, weekly, 8);
        assertThat(plan).contains(new TransferRecommender.Transfer("s", "A", "B", 19), new TransferRecommender.Transfer("s", "A", "C", 10));
        assertThat(plan).noneMatch(t -> t.toNode().equals("D"));   // D has 20 weeks of cover: a giver, not a taker
        assertThat(TransferRecommender.plan("s", Map.of("A", 50, "B", 60), Map.of(), 8)).as("no demand anywhere").isEmpty();
    }

    @Test void supplierScorePenalisesShortAndLateReceipts() {
        Instant t = Instant.now();
        var onTimeFull = new InventoryLearningRepository.Receipt("s", "a", "x", "n", 10, 10, t, t);
        var lateHalf = new InventoryLearningRepository.Receipt("s", "b", "x", "n", 10, 5, t, t.plus(Duration.ofDays(3)));
        assertThat(InboundReliabilityLearner.score(onTimeFull)).isEqualTo(1.0);
        assertThat(InboundReliabilityLearner.score(lateHalf)).isEqualTo(0.25);
        assertThat(InboundReliabilityLearner.shrink(List.of())).isEqualTo(0.9);
    }

    @Test void backorderTransitionsMapToResolutionsAndInventoryOutcomes() {
        Event kept = transition("backordered", "awaiting_sourcing");
        Event cancelled = transition("backordered", "cancelled");
        Event other = transition("released", "picking");
        assertThat(BackorderOutcomeLearner.resolutionOf(kept)).contains(new BackorderOutcomeLearner.Resolution("O1", "L1", "converted"));
        assertThat(BackorderOutcomeLearner.resolutionOf(cancelled)).contains(new BackorderOutcomeLearner.Resolution("O1", "L1", "cancelled"));
        assertThat(BackorderOutcomeLearner.resolutionOf(other)).isEmpty();
        InventoryOutcomeMapper mapper = new InventoryOutcomeMapper(30);
        assertThat(mapper.map(cancelled)).isEqualTo(new OutcomeData.InventoryOutcome(false, true, 0));
        assertThat(mapper.map(other)).isNull();
        Event youngSale = new Event("e", "t", "reservation", "r", 1, "reservation.consumed", Map.of("orderId", "O1", "stockAgeDays", 3), Actor.system("t"), null, null, Map.of(), Instant.now());
        Event agedSale = new Event("e", "t", "reservation", "r", 1, "reservation.consumed", Map.of("orderId", "O1", "stockAgeDays", 64), Actor.system("t"), null, null, Map.of(), Instant.now());
        assertThat(mapper.map(youngSale)).isNull();
        assertThat(mapper.map(agedSale)).isEqualTo(new OutcomeData.InventoryOutcome(false, false, 64));
    }

    private static Event transition(String from, String to) {
        return new Event("e-" + from + to, "t", "order", "O1", 1, "order.transitioned",
            Map.of("entityType", "order_line", "entityId", "O1:L1", "from", from, "to", to), Actor.system("t"), null, "O1", Map.of(), Instant.now());
    }
}
