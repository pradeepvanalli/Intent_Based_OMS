package ai.intentoms.order.api;

import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UI filters → OrderQuery, including the age buckets of the control tower. */
class OrderQueryMapperTest {
    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void mapsFiltersAndAgeBucket() {
        OrderQuery q = OrderController.OrderQueryMapper.map("jane", "released, sourcing", "web", "standard_ship", "dc-1", "c-1", "2026-10-01T00:00:00Z", null, "1–4h", null, null, 10, 20, NOW);
        assertThat(q.text()).isEqualTo("jane");
        assertThat(q.statuses()).containsExactly("released", "sourcing");
        assertThat(q.channel()).isEqualTo("web");
        assertThat(q.nodeId()).isEqualTo("dc-1");
        assertThat(q.placedFrom()).isEqualTo(NOW.minus(Duration.ofHours(4)));
        assertThat(q.placedTo()).isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(q.offset()).isEqualTo(10);
        assertThat(q.limit()).isEqualTo(20);

        OrderQuery young = OrderController.OrderQueryMapper.map(null, null, null, null, null, null, null, null, "< 1h", null, null, 0, 50, NOW);
        assertThat(young.placedFrom()).isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(young.placedTo()).isNull();
        OrderQuery old = OrderController.OrderQueryMapper.map(null, null, null, null, null, null, null, null, "> 3d", null, null, 0, 50, NOW);
        assertThat(old.placedTo()).isEqualTo(NOW.minus(Duration.ofHours(72)));
        assertThat(old.placedFrom()).isNull();
        OrderQuery date = OrderController.OrderQueryMapper.map(null, null, null, null, null, null, "2026-10-01", null, null, null, null, 0, 50, NOW);
        assertThat(date.placedFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThatThrownBy(() -> OrderController.OrderQueryMapper.map(null, null, null, null, null, null, null, null, "weird", null, null, 0, 50, NOW)).isInstanceOf(ValidationException.class);
    }
}
