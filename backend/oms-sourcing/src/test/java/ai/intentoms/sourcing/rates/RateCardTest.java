package ai.intentoms.sourcing.rates;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Rate card parsing, zone and weight-band selection, pickup, fallbacks and on-time probability (FR-SRC-02, FR-SRC-05). */
class RateCardTest {
    static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo", "shipping-rates.yaml").toAbsolutePath().normalize();

    static RateCard demo() {
        try { return RateCard.fromYaml(Files.readString(DEMO)); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @Test
    void parsesDemoCard() {
        RateCard card = demo();
        assertThat(card.services()).containsExactly("ground", "two_day", "next_day", "same_day");
        assertThat(card.splitPenaltyUsd()).isEqualTo(12.0);
        assertThat(card.onTimeTable()).containsEntry(1, 0.95).containsEntry(8, 0.85);
    }

    @Test
    void zoneIsFirstBandWhoseMaxKmCoversTheDistance() {
        RateCard card = demo();
        assertThat(card.quote("ground", 120, 0.4)).satisfies(q -> { assertThat(q.transitDays()).isEqualTo(1); assertThat(q.cost()).isEqualTo(Money.usd("6.20")); assertThat(q.zoneIndex()).isZero(); });
        assertThat(card.quote("ground", 300, 0.4).transitDays()).isEqualTo(1);        // inclusive upper bound
        assertThat(card.quote("ground", 300.1, 0.4).transitDays()).isEqualTo(2);
        assertThat(card.quote("ground", 2400, 0.4)).satisfies(q -> { assertThat(q.transitDays()).isEqualTo(3); assertThat(q.cost()).isEqualTo(Money.usd("8.60")); });
        assertThat(card.quote("ground", 4000, 0.4).transitDays()).isEqualTo(5);
        assertThat(card.quote("ground", 12000, 0.4).transitDays()).isEqualTo(8);  // last zone catches everything
    }

    @Test
    void weightBandIsSmallestBandAtOrAboveTheWeight() {
        RateCard card = demo();
        assertThat(card.quote("ground", 100, 0.5).cost()).isEqualTo(Money.usd("6.20"));   // exactly on the band edge
        assertThat(card.quote("ground", 100, 0.51).cost()).isEqualTo(Money.usd("8.10"));
        assertThat(card.quote("ground", 100, 2.0).cost()).isEqualTo(Money.usd("8.10"));
        assertThat(card.quote("ground", 100, 7.5).cost()).isEqualTo(Money.usd("14.50"));
        assertThat(card.quote("ground", 100, 19.9).cost()).isEqualTo(Money.usd("21.00"));
        assertThat(card.quote("ground", 100, 250).cost()).isEqualTo(Money.usd("34.00"));  // beyond last band → last band
        assertThat(card.quote("ground", 100, -1).cost()).isEqualTo(Money.usd("6.20"));    // negative treated as zero
        assertThat(card.quote("two_day", 3000, 3).weightBandKg()).isEqualTo(5.0);
    }

    @Test
    void pickupIsFreeAndUnknownServiceFallsBackToGround() {
        RateCard card = demo();
        RateCard.Quote pickup = card.quote("pickup", 999, 50);
        assertThat(pickup.cost().isZero()).isTrue();
        assertThat(pickup.transitDays()).isZero();
        assertThat(card.quote("overnight_rocket", 100, 0.4)).isEqualTo(card.quote("ground", 100, 0.4));
        assertThat(card.quote("NEXT_DAY", 100, 0.4).transitDays()).isEqualTo(1);
        assertThat(card.transitDays("same_day", 10)).isZero();
        assertThat(card.transitDays("same_day", 100)).isZero(); // single zone card: beyond maxKm still last zone
    }

    @Test
    void onTimeProbabilityUsesTableTimesReliabilityWithDecayBeyondTable() {
        RateCard card = demo();
        assertThat(card.onTimeProbability(1, 1.0)).isEqualTo(0.95);
        assertThat(card.onTimeProbability(4, 1.0)).isEqualTo(0.92);          // floor entry 3 → 0.92
        assertThat(card.onTimeProbability(2, 0.9)).isCloseTo(0.846, within(1e-9));
        assertThat(card.onTimeProbability(10, 1.0)).isCloseTo(0.81, within(1e-9)); // 0.85 − 0.02×2
        assertThat(card.onTimeProbability(100, 1.0)).isEqualTo(0.5);
        assertThat(card.onTimeProbability(1, 1.7)).isEqualTo(0.95);          // reliability clamped
        assertThat(card.onTimeProbability(-3, 1.0)).isEqualTo(0.88);
    }

    @Test
    void rejectsEmptyOrMalformedCards() {
        assertThatThrownBy(() -> RateCard.fromYaml("services: []")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RateCard.fromYaml("services:\n  - code: ground\n    zones: []")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RateCard.fromYaml("services:\n  - code: ground\n    zones:\n      - { maxKm: 10, transitDays: 1 }")).isInstanceOf(ValidationException.class);
    }
}
