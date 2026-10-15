package ai.intentoms.agent.ucp;

import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-AGT-02: version negotiation and lifecycle event mapping. */
class UcpVersionsTest {
    @Test
    void negotiation() {
        assertThat(UcpVersions.negotiate(null)).isEqualTo("2026-08-25");
        assertThat(UcpVersions.negotiate("  ")).isEqualTo("2026-08-25");
        assertThat(UcpVersions.negotiate("2026-08-25")).isEqualTo("2026-08-25");
        assertThatThrownBy(() -> UcpVersions.negotiate("2025-01-01")).isInstanceOf(OmsException.class).satisfies(e -> {
            OmsException oe = (OmsException) e;
            assertThat(oe.status().value()).isEqualTo(400);
            assertThat(oe.code()).isEqualTo("unsupported_ucp_version");
            assertThat(oe.details().get("supported_versions")).isEqualTo(List.of("2026-08-25"));
        });
    }

    private static Event event(String aggType, String aggId, String type, Map<String, Object> payload) {
        return new Event("evt-1", "test", aggType, aggId, 5, type, payload, Actor.system("t"), null, null, Map.of(), Instant.now());
    }

    @Test
    void mapsLifecycleEventsOnly() {
        assertThat(UcpWebhookSubscriber.map(event("order", "o1", "order.transitioned", Map.of("entityType", "order", "from", "released", "to", "shipped")))).get()
            .satisfies(m -> { assertThat(m.type()).isEqualTo("order.shipped"); assertThat(m.orderId()).isEqualTo("o1"); assertThat(m.data().get("partial")).isEqualTo(false); });
        assertThat(UcpWebhookSubscriber.map(event("order", "o1", "order.transitioned", Map.of("entityType", "order", "to", "partially_delivered")))).get()
            .satisfies(m -> { assertThat(m.type()).isEqualTo("order.delivered"); assertThat(m.data().get("partial")).isEqualTo(true); });
        assertThat(UcpWebhookSubscriber.map(event("order", "o1", "order.transitioned", Map.of("entityType", "order_line", "to", "shipped")))).as("line-level moves are not milestones").isEmpty();
        assertThat(UcpWebhookSubscriber.map(event("order", "o1", "order.transitioned", Map.of("entityType", "order", "to", "released")))).isEmpty();
        assertThat(UcpWebhookSubscriber.map(event("return", "rma_1", "return.requested", Map.of("orderId", "o1", "riskScore", 3)))).get()
            .satisfies(m -> { assertThat(m.type()).isEqualTo("return.created"); assertThat(m.orderId()).isEqualTo("o1"); assertThat(m.data()).containsEntry("return_id", "rma_1").doesNotContainKey("riskScore"); });
        assertThat(UcpWebhookSubscriber.map(event("return", "rma_1", "return.refunded", Map.of("orderId", "o1")))).get().satisfies(m -> assertThat(m.type()).isEqualTo("refund.issued"));
        assertThat(UcpWebhookSubscriber.map(event("order", "o1", "order.captured", Map.of()))).isEmpty();
    }

    @Test
    void snakeCase() {
        assertThat(UcpViews.toSnake("channelOrderId")).isEqualTo("channel_order_id");
        assertThat(UcpViews.snake(Map.of("lineId", "L1", "unitPrice", Map.of("amount", 1))).toString()).contains("line_id").contains("unit_price");
    }
}
