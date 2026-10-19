package ai.intentoms.learning.service;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** OC-05: rule-based attribution of a bad outcome across stock, node, carrier and customer. */
class OutcomeAttributionTest {
    private static OutcomeRecord out(OutcomeData d) { return new OutcomeRecord("o", "e", d.getClass().getSimpleName(), d, Instant.now(), "ev", Instant.now()); }

    @Test void nothingWentWrongMeansNoBlame() {
        var outs = List.of(out(new OutcomeData.FulfillmentOutcome(1, true, List.of("n1"), false, 2, 2, 5, 50)), out(new OutcomeData.DeliveryOutcome(true, 0, false, false)));
        assertThat(OutcomeAttribution.attribute(outs)).isEmpty();
        assertThat(OutcomeAttribution.primary(Map.of())).isNull();
    }

    @Test void lostParcelIsTheCarrier() {
        Map<String, Double> a = OutcomeAttribution.attribute(List.of(out(new OutcomeData.DeliveryOutcome(false, 48, false, true))));
        assertThat(a).containsEntry("attr.carrier", 1.0).containsEntry("attr.node", 0.0);
        assertThat(OutcomeAttribution.primary(a)).isEqualTo("carrier");
    }

    @Test void lateAfterAStoreRejectLeansOnTheNode() {
        var outs = List.of(out(new OutcomeData.FulfillmentOutcome(1, false, List.of("n2"), true, 1, 1, 5, 50)), out(new OutcomeData.DeliveryOutcome(false, 30, false, false)));
        Map<String, Double> a = OutcomeAttribution.attribute(outs);
        assertThat(OutcomeAttribution.primary(a)).isEqualTo("node");
        assertThat(a.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(0.002));
    }

    @Test void aWonChargebackBlamesNobody() {
        // Audit A-N2: the dispute and the merchant's win cancel each other.
        var outs = List.of(out(new OutcomeData.PaymentOutcome(true, false, 120)), out(new OutcomeData.PaymentOutcome(true, false, -120)));
        assertThat(OutcomeAttribution.attribute(outs)).isEmpty();
        var lost = List.of(out(new OutcomeData.PaymentOutcome(true, false, 120)), out(new OutcomeData.PaymentOutcome(true, false, 80)), out(new OutcomeData.PaymentOutcome(true, false, -120)));
        assertThat(OutcomeAttribution.primary(OutcomeAttribution.attribute(lost))).as("one of two disputes won").isEqualTo("customer");
    }

    @Test void chargebackAndRefusalAreTheCustomer() {
        assertThat(OutcomeAttribution.primary(OutcomeAttribution.attribute(List.of(out(new OutcomeData.PaymentOutcome(true, false, 120))))) ).isEqualTo("customer");
        assertThat(OutcomeAttribution.primary(OutcomeAttribution.attribute(List.of(out(new OutcomeData.PaymentOutcome(false, true, 80))))) ).isEqualTo("customer");
    }

    @Test void splitIsStock() {
        assertThat(OutcomeAttribution.attribute(List.of(out(new OutcomeData.FulfillmentOutcome(2, false, List.of("a", "b"), false, 3, 3, 9, 90)))))
            .containsEntry("attr.stock", 1.0);
    }

    @Test void twoParcelsFromTwoNodesAreStock() {
        var outs = List.of(
            out(new OutcomeData.FulfillmentOutcome(2, false, List.of("DC-NJ"), false, 2, 2, 9, 90, "fg1", "sh1")),
            out(new OutcomeData.FulfillmentOutcome(2, false, List.of("DC-TX"), false, 1, 1, 7, 90, "fg2", "sh2")));
        assertThat(OutcomeAttribution.attribute(outs)).containsEntry("attr.stock", 1.0);
    }

    @Test void oneNodeShippingItsPackageInTwoParcelsIsNotStock() {
        // Audit A7: one group at one node, shipped as two parcels. Every unit was in stock there.
        var outs = List.of(
            out(new OutcomeData.FulfillmentOutcome(1, false, List.of("DC-NJ"), false, 3, 2, 9, 90, "fg1", "sh1")),
            out(new OutcomeData.FulfillmentOutcome(1, false, List.of("DC-NJ"), false, 3, 1, 9, 90, "fg1", "sh2")));
        Map<String, Double> a = OutcomeAttribution.attribute(outs);
        assertThat(a).containsEntry("attr.stock", 0.0).containsEntry("attr.node", 1.0);
        assertThat(OutcomeAttribution.primary(a)).isEqualTo("node");
    }
}
