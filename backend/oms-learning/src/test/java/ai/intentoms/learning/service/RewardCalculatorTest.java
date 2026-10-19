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
        // A refused cash-on-delivery parcel comes back: its loss is the return leg (the outbound cost again, 5 on a 100 order).
        assertThat(RewardCalculator.components(List.of(shipped, out(new OutcomeData.PaymentOutcome(false, true, 100.0)))).get("fraud")).isCloseTo(0.05, within(1e-9));
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

    @Test void anOrderLevelFillCountsBackorderedAndCancelledUnits() {
        // 5 units ordered; one group planned 3 and shipped them; 2 units backordered then cancelled, never in a group.
        var parcel = out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n"), false, 3, 3, 5.0, 100.0, "fg1", "sh1", 5, true));
        assertThat(RewardCalculator.components(List.of(parcel), new RewardCalculator.Scope(0, true)).get("fill")).isCloseTo(0.6, within(1e-9));
        assertThat(RewardCalculator.components(List.of(parcel), RewardCalculator.Scope.NONE).get("fill")).as("a group's own episode: its group").isEqualTo(1.0);
        // Older rows that did not report the order's units keep the per-group denominator.
        var legacy = out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n"), false, 3, 3, 5.0, 100.0, "fg1", "sh1"));
        assertThat(RewardCalculator.components(List.of(legacy), new RewardCalculator.Scope(0, true)).get("fill")).isEqualTo(1.0);
    }

    @Test void theWeightedFillIsTheFirstAttemptAndEveryAttemptIsAnUnweightedSideComponent() {
        var first = out(new OutcomeData.FulfillmentOutcome(2, false, List.of("a"), true, 2, 2, 5.0, 100.0, "fg1", "sh1", 4, true));
        var resourced = out(new OutcomeData.FulfillmentOutcome(2, false, List.of("b"), true, 2, 2, 5.0, 100.0, "fg2", "sh2", 4, false));
        Map<String, Double> c = RewardCalculator.components(List.of(first, resourced), new RewardCalculator.Scope(0, true));
        assertThat(c.get("fill")).as("§2: units shipped on the first attempt / units ordered").isCloseTo(0.5, within(1e-9));
        assertThat(c.get(RewardCalculator.FILL_ALL_ATTEMPTS)).isEqualTo(1.0);
        assertThat(RewardCalculator.components(List.of(out(new OutcomeData.ContactOutcome("cs", "wismo", false))))).doesNotContainKey(RewardCalculator.FILL_ALL_ATTEMPTS);
        assertThat(RewardCalculator.weight(ObjectiveWeights.preset("balanced"), RewardCalculator.FILL_ALL_ATTEMPTS)).as("not weighted").isZero();
        ObjectiveWeights fillOnly = new ObjectiveWeights(1, 0, 0, 0, 0, 0, 0);
        assertThat(RewardCalculator.compute(c, fillOnly)).as("the reward uses the first-attempt fill").isCloseTo(0.5, within(1e-9));
    }

    private static final RewardCalculator.Scope ORDER = new RewardCalculator.Scope(0, true);
    private static OutcomeRecord shippedAll() { return out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n"), false, 3, 3, 5.0, 100.0, "fg1", "sh1", 3, true)); }
    private static OutcomeRecord arrived(boolean onTime) { return out(new OutcomeData.DeliveryOutcome(onTime, onTime ? 0 : 30, false, false, true)); }

    @Test void aPerfectOrderIsDeliveredCompleteOnTimeFromItsFirstSourcingWithNothingAfter() {
        assertThat(RewardCalculator.components(List.of(shippedAll()), ORDER)).as("on its way").doesNotContainKey(RewardCalculator.PERFECT);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true)), ORDER)).containsEntry(RewardCalculator.PERFECT, 1.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true)), RewardCalculator.Scope.NONE)).as("only an order-level episode")
            .doesNotContainKey(RewardCalculator.PERFECT);

        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(false)), ORDER)).as("late").containsEntry(RewardCalculator.PERFECT, 0.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), out(new OutcomeData.DeliveryOutcome(true, 0, true, false, true))), ORDER)).as("damaged")
            .containsEntry(RewardCalculator.PERFECT, 0.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true), out(new OutcomeData.ContactOutcome("cs", "wismo", false))), ORDER)).as("a contact is cx, not the north star")
            .containsEntry(RewardCalculator.PERFECT, 1.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true), out(new OutcomeData.ReturnOutcome("defective", "refurbish", false, 20.0, "rma_1", true, "damaged", null))), ORDER))
            .as("a return graded damaged").containsEntry(RewardCalculator.PERFECT, 0.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true), out(new OutcomeData.ReturnOutcome("arrived_late", "restock", false, 20.0, "rma_1", true))), ORDER))
            .as("returned because it came late").containsEntry(RewardCalculator.PERFECT, 0.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true), out(new OutcomeData.ReturnOutcome("wrong_size", "restock", false, 20.0, "rma_1", true))), ORDER))
            .as("a size exchange is not a failure").containsEntry(RewardCalculator.PERFECT, 1.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true), out(new OutcomeData.PaymentOutcome(true, false, 100.0))), ORDER)).as("a chargeback is fraud, not the north star")
            .containsEntry(RewardCalculator.PERFECT, 1.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true), out(new OutcomeData.ReturnOutcome("changed_mind", "restock", false, 20.0, "rma_1", true, "damaged", null))), ORDER))
            .as("a change of mind is never a fulfilment failure").containsEntry(RewardCalculator.PERFECT, 1.0);
        assertThat(RewardCalculator.components(List.of(shippedAll(), arrived(true), out(new OutcomeData.ReturnOutcome("wrong_item", "destroy", true, 20.0, "rma_1", true, "wrong_item", null))), ORDER))
            .as("a wrong-item claim graded as abuse is not the fulfilment's fault").containsEntry(RewardCalculator.PERFECT, 1.0);
        var shortShip = out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n"), false, 2, 2, 5.0, 100.0, "fg1", "sh1", 3, true));
        assertThat(RewardCalculator.components(List.of(shortShip, arrived(true)), ORDER)).as("incomplete").containsEntry(RewardCalculator.PERFECT, 0.0);
        var resourced = out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n"), true, 3, 3, 5.0, 100.0, "fg2", "sh1", 3, false));
        assertThat(RewardCalculator.components(List.of(resourced, arrived(true)), ORDER)).as("not the first attempt").containsEntry(RewardCalculator.PERFECT, 0.0);
    }

    @Test void perfectWaitsForTheLastParcelButNotForAParcelThatWillNeverArrive() {
        var first = out(new OutcomeData.FulfillmentOutcome(2, false, List.of("a"), false, 1, 1, 5.0, 100.0, "fg1", "sh1", 2, true));
        var second = out(new OutcomeData.FulfillmentOutcome(2, false, List.of("b"), false, 1, 1, 5.0, 100.0, "fg2", "sh2", 2, true));
        var firstArrived = out(new OutcomeData.DeliveryOutcome(true, 0, false, false, false));
        assertThat(RewardCalculator.components(List.of(first, second, firstArrived), ORDER)).doesNotContainKey(RewardCalculator.PERFECT);
        assertThat(RewardCalculator.components(List.of(first, second, firstArrived, arrived(true)), ORDER)).containsEntry(RewardCalculator.PERFECT, 1.0);
        assertThat(RewardCalculator.components(List.of(first, second, firstArrived, out(new OutcomeData.DeliveryOutcome(false, 0, false, true, false))), ORDER))
            .as("the other parcel was lost").containsEntry(RewardCalculator.PERFECT, 0.0);
        assertThat(RewardCalculator.components(List.of(first, out(new OutcomeData.PaymentOutcome(false, true, 100.0))), ORDER)).as("refused at the door")
            .containsEntry(RewardCalculator.PERFECT, 0.0);
    }

    @Test void perfectCarriesNoWeight() {
        Map<String, Double> c = RewardCalculator.components(List.of(shippedAll(), arrived(true)), ORDER);
        Map<String, Double> without = new java.util.LinkedHashMap<>(c);
        without.remove(RewardCalculator.PERFECT);
        ObjectiveWeights w = ObjectiveWeights.preset("balanced");
        assertThat(RewardCalculator.compute(c, w)).isEqualTo(RewardCalculator.compute(without, w));
        assertThat(RewardCalculator.weight(w, RewardCalculator.PERFECT)).isZero();
        assertThat(RewardCalculator.TERMS).doesNotContain(RewardCalculator.PERFECT, RewardCalculator.PERFECT_LINES, RewardCalculator.FILL_ALL_ATTEMPTS);
    }

    @Test void penaltyTermsAreScaledAgainstTheTenantBaselineAndKeepTheirRawValue() {
        Map<String, Double> c = new java.util.LinkedHashMap<>(Map.of("fill", 1.0, "cost", 0.06, "split", 0.5, "cx", 0.0));
        Map<String, Double> n = RewardCalculator.normalise(c, Map.of("cost", 0.12, "split", 0.25));
        assertThat(n.get("cost")).isCloseTo(0.5, within(1e-9));
        assertThat(n.get("cost_raw")).isEqualTo(0.06);
        assertThat(n.get("split")).isEqualTo(1.0); // capped at 1
        assertThat(n.get("fill")).isEqualTo(1.0);  // rate terms use the identity
        assertThat(RewardCalculator.normalise(c, Map.of())).as("defaults are each term's natural range").isEqualTo(c);
    }

    @Test void normalisationIsNeverOffAgedStockReliefIsScaledInDays() {
        Map<String, Double> c = RewardCalculator.components(List.of(out(new OutcomeData.InventoryOutcome(false, false, 45))));
        assertThat(c.get("inv")).as("measured as the age of the aged stock sold").isEqualTo(45.0);
        Map<String, Double> withDefaults = RewardCalculator.normalise(c, Map.of());
        assertThat(withDefaults.get("inv")).as("default scale: 30 days, any aged sale is full relief").isEqualTo(1.0);
        assertThat(withDefaults.get("inv_raw")).isEqualTo(45.0);
        Map<String, Double> scaled = RewardCalculator.normalise(c, Map.of("inv", 90.0));
        assertThat(scaled.get("inv")).as("against a baseline where aged stock typically sells at 90 days").isCloseTo(0.5, within(1e-9));
        assertThat(RewardCalculator.DEFAULT_SCALES).containsOnlyKeys(RewardCalculator.TERMS);
    }

    private static OutcomeData.FulfillmentOutcome lineParcel(String shipment, Map<String, Integer> lineUnits, boolean firstAttempt) {
        int units = lineUnits.values().stream().mapToInt(Integer::intValue).sum();
        return new OutcomeData.FulfillmentOutcome(2, false, List.of("n"), false, units, units, 5.0, 100.0, "fg-" + shipment, shipment, 4, firstAttempt,
            lineUnits, Map.of("L1", 2, "L2", 1, "L3", 1));
    }

    private static OutcomeRecord deliveredParcel(String shipment, List<String> lines, boolean onTime, boolean damaged, boolean all) {
        return out(new OutcomeData.DeliveryOutcome(onTime, onTime ? 0 : 30, damaged, false, all, lines, List.of(shipment)));
    }

    @Test void thePerfectOrderRateIsJudgedPerLine() {
        var a = out(lineParcel("sh1", Map.of("L1", 2, "L2", 1), true));
        var b = out(lineParcel("sh2", Map.of("L3", 1), true));
        assertThat(RewardCalculator.components(List.of(a, b, deliveredParcel("sh1", List.of("L1", "L2"), true, false, false)), ORDER)).as("one parcel still on its way")
            .doesNotContainKey(RewardCalculator.PERFECT_LINES);
        Map<String, Double> all = RewardCalculator.components(List.of(a, b, deliveredParcel("sh1", List.of("L1", "L2"), true, false, false),
            deliveredParcel("sh2", List.of("L3"), true, false, true)), ORDER);
        assertThat(all).containsEntry(RewardCalculator.PERFECT_LINES, 1.0).containsEntry(RewardCalculator.LINES, 3.0).containsEntry(RewardCalculator.PERFECT, 1.0);

        Map<String, Double> damaged = RewardCalculator.components(List.of(a, b, deliveredParcel("sh1", List.of("L1", "L2"), true, false, false),
            deliveredParcel("sh2", List.of("L3"), true, true, true)), ORDER);
        assertThat(damaged.get(RewardCalculator.PERFECT_LINES)).as("the damaged parcel's line only").isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(damaged).containsEntry(RewardCalculator.PERFECT, 0.0);

        var lineReturn = out(new OutcomeData.ReturnOutcome("defective", "refurbish", false, 20.0, "rma_1", true, "damaged",
            List.of(new OutcomeData.ReturnedLine("L2", "defective", "damaged"))));
        Map<String, Double> returned = RewardCalculator.components(List.of(a, b, deliveredParcel("sh1", List.of("L1", "L2"), true, false, false),
            deliveredParcel("sh2", List.of("L3"), true, false, true), lineReturn), ORDER);
        assertThat(returned.get(RewardCalculator.PERFECT_LINES)).as("only the returned line").isCloseTo(2.0 / 3.0, within(1e-9));

        var resourced = out(lineParcel("sh2", Map.of("L3", 1), false));
        Map<String, Double> late = RewardCalculator.components(List.of(a, resourced, deliveredParcel("sh1", List.of("L1", "L2"), true, false, false),
            deliveredParcel("sh2", List.of("L3"), true, false, true)), ORDER);
        assertThat(late.get(RewardCalculator.PERFECT_LINES)).as("a line shipped by a re-sourcing is not complete on the first attempt").isCloseTo(2.0 / 3.0, within(1e-9));
    }
}
