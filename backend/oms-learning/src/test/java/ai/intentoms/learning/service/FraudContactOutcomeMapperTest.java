package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OC-03 mappers for chargebacks, refused deliveries and contacts; OC-02 outcome-to-episode scoping. */
class FraudContactOutcomeMapperTest {
    private final FraudContactOutcomeMapper mapper = new FraudContactOutcomeMapper();

    private static Event event(String type, String aggregate, Map<String, Object> payload) {
        return new Event("ev1", "t", aggregate, "id1", 1, type, payload, Actor.system("test"), null, "ord1", Map.of(), Instant.now());
    }

    @Test void mapsEachSource() {
        assertThat(mapper.map(event("payment.chargeback", "payment", Map.of("orderId", "ord1", "amount", 120.5))))
            .isEqualTo(new OutcomeData.PaymentOutcome(true, false, 120.5));
        assertThat(mapper.map(event("order.returned_to_sender", "order", Map.of("cod", true, "amount", 80))))
            .isEqualTo(new OutcomeData.PaymentOutcome(false, true, 80));
        assertThat(mapper.map(event("order.returned_to_sender", "order", Map.of("cod", false))))
            .isEqualTo(new OutcomeData.DeliveryOutcome(false, 0, false, false));
        assertThat(mapper.map(event("cs.complaint", "order", Map.of("channel", "phone"))))
            .isEqualTo(new OutcomeData.ContactOutcome("phone", "complaint", true));
        assertThat(mapper.map(event("cs.contact", "order", Map.of())))
            .isEqualTo(new OutcomeData.ContactOutcome("cs", "other", false));
    }

    @Test void groupAndLineScopedOutcomesOnlyReachTheirEpisodes() {
        Episode orderLevel = Episode.open("e1", "d1", "sourcing_node_score", "ord1", null, null, 1, "order", "ord1", Instant.now());
        Episode groupA = Episode.open("e2", "d2", "x", "ord1", null, "fg_a", 1, "order", "ord1", Instant.now());
        Episode line2 = Episode.open("e3", "d3", "y", "ord1", "2", null, 1, "line", "ord1:2", Instant.now());
        Event shippedA = event("order.shipped", "order", Map.of("fulfilmentGroupId", "fg_a", "lineIds", List.of("1")));
        Event shippedB = event("order.shipped", "order", Map.of("fulfilmentGroupId", "fg_b", "lines", Map.of("2", 1)));

        assertThat(OutcomeObserver.concerns(shippedA, orderLevel)).isTrue();
        assertThat(OutcomeObserver.concerns(shippedA, groupA)).isTrue();
        assertThat(OutcomeObserver.concerns(shippedB, groupA)).isFalse();
        assertThat(OutcomeObserver.concerns(shippedA, line2)).isFalse();
        assertThat(OutcomeObserver.concerns(shippedB, line2)).isTrue();
        assertThat(OutcomeObserver.concerns(event("order.delivered", "order", Map.of()), line2)).isTrue();
    }
}
