package ai.intentoms.learning.service;

import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RewardCalculatorTest {
    private static OutcomeRecord out(OutcomeData d) {
        return new OutcomeRecord("o", "e", d.getClass().getSimpleName(), d, Instant.now(), "ev", Instant.now());
    }

    @Test void everyTermIsNormalisedToZeroOne() {
        var outs = List.of(
            out(new OutcomeData.FulfillmentOutcome(2, false, List.of("n1", "n2"), false, 4, 3, 30.0, 120.0)),
            out(new OutcomeData.DeliveryOutcome(true, 0, false, false)),
            out(new OutcomeData.ContactOutcome("cs", "wismo", false)),
            out(new OutcomeData.PaymentOutcome(true, false, 500.0)));
        Map<String, Double> c = RewardCalculator.components(outs);
        assertThat(c.get("fill")).isEqualTo(0.75);
        assertThat(c.get("cost")).isEqualTo(0.25);
        assertThat(c.get("split")).isEqualTo(0.5);
        assertThat(c.get("otd")).isEqualTo(1.0);
        assertThat(c.get("cx")).isEqualTo(0.5);
        assertThat(c.get("fraud")).isEqualTo(1.0); // 500 loss on a 120 order caps at 1
        c.values().forEach(v -> assertThat(v).isBetween(0.0, 1.0));
    }

    @Test void unreportedFiguresLeaveTheirTermsUnscored() {
        var outs = List.of(out(new OutcomeData.FulfillmentOutcome(1, false, List.of(), false, 0, 0, 0, 0)));
        assertThat(RewardCalculator.scoredTerms(outs)).containsExactly("split");
        Map<String, Double> c = RewardCalculator.components(outs);
        assertThat(c.get("fill")).isZero();
        assertThat(c.get("cost")).isZero();
        assertThat(c.get("split")).isZero();
    }

    @Test void rewardAppliesWeightsWithSigns() {
        Map<String, Double> c = Map.of("fill", 1.0, "otd", 1.0, "cost", 0.2, "split", 0.5, "fraud", 0.0, "cx", 0.0, "inv", 0.0);
        assertThat(RewardCalculator.compute(c, ObjectiveWeights.preset("balanced"))).isCloseTo(1.0 + 1.0 - 0.2 - 0.5, within(1e-9));
    }

    @Test void maturityWindowsPerTerm() {
        MaturityWindows w = MaturityWindows.DEFAULT;
        assertThat(w.forTerm("otd").toDays()).isEqualTo(14);
        assertThat(w.forTerm("cx").toDays()).isEqualTo(30);
        assertThat(w.forTerm("fraud").toDays()).isEqualTo(120);
        assertThat(w.longest().toDays()).isEqualTo(120);
    }

    private static OutcomeData.FulfillmentOutcome parcel(String group, String shipment, int groupUnits, int shipped, double groupCost) {
        return new OutcomeData.FulfillmentOutcome(2, false, List.of("n"), false, groupUnits, shipped, groupCost, 100.0, group, shipment);
    }

    @Test void oneParcelIsNoSplitAndCountsOnce() {
        Map<String, Double> c = RewardCalculator.components(List.of(out(parcel("fg1", "sh1", 3, 3, 12.0))));
        assertThat(c.get("split")).isZero();
        assertThat(c.get("fill")).isCloseTo(1.0, within(1e-9));
        assertThat(c.get("cost")).isCloseTo(0.12, within(1e-9));
    }

    @Test void oneGroupInTwoParcelsCountsTheGroupOnce() {
        // The audit's ord_4mh34mvhp4rc: 3/3 units in two parcels scored fill 0.5 and cost 0.254 before.
        Map<String, Double> c = RewardCalculator.components(List.of(out(parcel("fg1", "sh1", 3, 2, 12.7)), out(parcel("fg1", "sh2", 3, 1, 12.7))));
        assertThat(c.get("fill")).isCloseTo(1.0, within(1e-9));
        assertThat(c.get("cost")).isCloseTo(0.127, within(1e-9));
        assertThat(c.get("split")).isCloseTo(0.5, within(1e-9));
    }

    @Test void threeParcelsAcrossTwoGroups() {
        Map<String, Double> c = RewardCalculator.components(List.of(
            out(parcel("fg1", "sh1", 2, 2, 10.0)), out(parcel("fg2", "sh2", 2, 1, 8.0)), out(parcel("fg2", "sh3", 2, 1, 8.0))));
        assertThat(c.get("fill")).isCloseTo(1.0, within(1e-9));       // 4 shipped of 2 + 2 ordered
        assertThat(c.get("cost")).isCloseTo(0.18, within(1e-9));      // 10 + 8, each group once
        assertThat(c.get("split")).isCloseTo(1.0, within(1e-9));      // three parcels
    }

    @Test void redeliveredParcelEventDoesNotAddAPackage() {
        Map<String, Double> c = RewardCalculator.components(List.of(out(parcel("fg1", "sh1", 3, 3, 12.0)), out(parcel("fg1", "sh1", 3, 0, 12.0))));
        assertThat(c.get("split")).isZero();
    }

    @Test void onTimeIsTheShareOfDeliveredGroupsOnTime() {
        Map<String, Double> c = RewardCalculator.components(List.of(
            out(new OutcomeData.DeliveryOutcome(true, 0, false, false)), out(new OutcomeData.DeliveryOutcome(false, 30, false, false))));
        assertThat(c.get("otd")).isCloseTo(0.5, within(1e-9));
    }

    @Test void refusedCashOnDeliveryAndChargebacksAreLossesAndAWonChargebackCancels() {
        var shipped = out(parcel("fg1", "sh1", 1, 1, 5.0));
        assertThat(RewardCalculator.components(List.of(shipped, out(new OutcomeData.PaymentOutcome(false, true, 100.0)))).get("fraud")).isCloseTo(1.0, within(1e-9));
        assertThat(RewardCalculator.components(List.of(shipped, out(new OutcomeData.PaymentOutcome(true, false, 50.0)))).get("fraud")).isCloseTo(0.5, within(1e-9));
        assertThat(RewardCalculator.components(List.of(shipped, out(new OutcomeData.PaymentOutcome(true, false, 50.0)), out(new OutcomeData.PaymentOutcome(true, false, -50.0))))
            .get("fraud")).isZero();
        assertThat(RewardCalculator.components(List.of(shipped, out(new OutcomeData.ReturnOutcome("wrong_item", "restock", true, 40.0)))).get("fraud")).isCloseTo(0.4, within(1e-9));
    }

    @Test void aReturnIsOneLossKeyedByRmaAndTheIssuedRefundReplacesThePendingEstimate() {
        // Audit A2: the disposition (line value 68.0 while the refund is pending) and the refund (72.5 issued) of one
        // return were summed (140.5 on a 102.36 order, clamped to 1.0).
        var shipped = out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n"), false, 2, 2, 5.0, 102.36, "fg1", "sh1"));
        var disposition = out(new OutcomeData.ReturnOutcome("not_as_described", null, true, 68.0, "rma_1", false));
        var refund = out(new OutcomeData.ReturnOutcome("not_as_described", "refunded", true, 72.5, "rma_1", true));
        var manual = out(new OutcomeData.ReturnOutcome("not_as_described", "scrap", true, 72.5, "rma_1", false));
        assertThat(RewardCalculator.components(List.of(shipped, disposition, refund, manual)).get("fraud")).isCloseTo(72.5 / 102.36, within(1e-9));
        // Two returns of the same order are two losses.
        var other = out(new OutcomeData.ReturnOutcome("not_as_described", "scrap", true, 10.0, "rma_2", true));
        assertThat(RewardCalculator.components(List.of(shipped, disposition, refund, other)).get("fraud")).isCloseTo(82.5 / 102.36, within(1e-9));

        OutcomeData.ReturnOutcome merged = new OutcomeData.ReturnOutcome("not_as_described", null, true, 68.0, "rma_1", false)
            .merge(new OutcomeData.ReturnOutcome("not_as_described", "refunded", true, 72.5, "rma_1", true))
            .merge(new OutcomeData.ReturnOutcome("not_as_described", "scrap", true, 68.0, "rma_1", false));
        assertThat(merged).isEqualTo(new OutcomeData.ReturnOutcome("not_as_described", "scrap", true, 72.5, "rma_1", true));
        assertThat(merged.merge(new OutcomeData.ReturnOutcome("not_as_described", "refunded", true, 72.5, "rma_1", true))).as("idempotent").isEqualTo(merged);
    }

    @Test void anEpisodeWithNoOrderValueInItsOutcomesUsesTheOrdersValue() {
        // Audit A4: an episode opened after shipment never sees order.shipped, so the loss was scored as the whole order.
        var refund = out(new OutcomeData.ReturnOutcome("not_as_described", "scrap", true, 72.5, "rma_1", true));
        assertThat(RewardCalculator.needsOrderValue(List.of(refund))).isTrue();
        assertThat(RewardCalculator.components(List.of(refund)).get("fraud")).isEqualTo(1.0);
        assertThat(RewardCalculator.components(List.of(refund), 102.36).get("fraud")).isCloseTo(72.5 / 102.36, within(1e-9));
        var shipped = out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n"), false, 2, 2, 5.0, 200.0, "fg1", "sh1"));
        assertThat(RewardCalculator.needsOrderValue(List.of(shipped, refund))).isFalse();
        assertThat(RewardCalculator.components(List.of(shipped, refund), 102.36).get("fraud")).as("an outcome's own value wins").isCloseTo(72.5 / 200.0, within(1e-9));
        assertThat(RewardCalculator.needsOrderValue(List.of(out(new OutcomeData.ContactOutcome("cs", "wismo", false))))).as("no loss, nothing to look up").isFalse();
    }

    @Test void penaltyTermsAreScaledAgainstTheTenantBaselineAndKeepTheirRawValue() {
        Map<String, Double> c = new java.util.LinkedHashMap<>(Map.of("fill", 1.0, "cost", 0.06, "split", 0.5, "cx", 0.0));
        Map<String, Double> n = RewardCalculator.normalise(c, Map.of("cost", 0.12, "split", 0.25));
        assertThat(n.get("cost")).isCloseTo(0.5, within(1e-9));
        assertThat(n.get("cost_raw")).isEqualTo(0.06);
        assertThat(n.get("split")).isEqualTo(1.0); // capped at 1
        assertThat(n.get("fill")).isEqualTo(1.0);  // rate terms are not scaled
        assertThat(RewardCalculator.normalise(c, Map.of())).isSameAs(c);
    }
}
