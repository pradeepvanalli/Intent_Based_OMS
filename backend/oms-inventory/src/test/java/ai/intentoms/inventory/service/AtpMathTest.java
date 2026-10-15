package ai.intentoms.inventory.service;

import ai.intentoms.inventory.model.Atp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure ATP arithmetic (FR-INV-01) and channel buffer rounding (FR-INV-03). */
class AtpMathTest {

    @Test
    void availableExcludesInboundUnlessConfigured() {
        assertThat(AtpMath.available(100, 40, false, 10, 5, 15)).isEqualTo(70);
        assertThat(AtpMath.available(100, 40, true, 10, 5, 15)).isEqualTo(110);
    }

    @Test
    void availableCanGoNegativeWhenOversold() {
        assertThat(AtpMath.available(3, 0, false, 5, 0, 0)).isEqualTo(-2);
    }

    @Test
    void networkTotalClampsNegativeNodesToZero() {
        List<Atp> nodes = List.of(
            new Atp("s", "a", 10, 0, 0, 0, 0, 10, 1.0),
            new Atp("s", "b", 1, 0, 4, 0, 0, -3, 1.0),
            new Atp("s", "c", 7, 0, 2, 0, 0, 5, 1.0));
        assertThat(AtpMath.networkTotal(nodes)).isEqualTo(15);
    }

    @Test
    void bufferRoundsDownAndIsExactInDecimal() {
        assertThat(AtpMath.applyBuffer(10, 0.0)).isEqualTo(10);
        assertThat(AtpMath.applyBuffer(10, 0.1)).isEqualTo(9);
        assertThat(AtpMath.applyBuffer(10, 0.15)).isEqualTo(8);    // 8.5 -> 8
        assertThat(AtpMath.applyBuffer(10, 0.9)).isEqualTo(1);     // 10 * 0.1 must be 1, not 0.999.. -> 0
        assertThat(AtpMath.applyBuffer(3, 0.5)).isEqualTo(1);      // 1.5 -> 1
        assertThat(AtpMath.applyBuffer(7, 0.3)).isEqualTo(4);      // 4.9 -> 4
        assertThat(AtpMath.applyBuffer(1, 0.01)).isEqualTo(0);     // 0.99 -> 0
    }

    @Test
    void bufferClampsOutOfRangeFractionsAndNegativeTotals() {
        assertThat(AtpMath.applyBuffer(10, 1.5)).isEqualTo(0);
        assertThat(AtpMath.applyBuffer(10, -0.2)).isEqualTo(10);
        assertThat(AtpMath.applyBuffer(-4, 0.1)).isEqualTo(0);
    }
}
