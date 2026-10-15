package ai.intentoms.finance.landedcost;

import ai.intentoms.finance.FinanceTestBase;
import ai.intentoms.finance.model.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-FIN-05 tariff precedence (longest HS prefix, exact over wildcard) and effective dates. */
class LandedCostIntegrationTest extends FinanceTestBase {
    @Autowired LandedCostService landed;
    @Autowired TariffRateRepository tariffs;

    private static final Instant T2025 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T2026 = Instant.parse("2026-01-01T00:00:00Z");

    private TariffRate rate(String origin, String dest, String hs, String duty, String brokerage, String surcharge, Instant from, Instant to) {
        return tariffs.upsert(new TariffRate(null, origin, dest, hs, new BigDecimal(duty), new BigDecimal(brokerage), new BigDecimal(surcharge), from, to, null));
    }

    @Test
    void sameCountryIsFree() {
        LandedCost lc = landed.estimate("US", "us", "8471.30", Money.usd("500.00"), T2026);
        assertThat(lc.domestic()).isTrue();
        assertThat(lc.total()).isEqualTo(Money.zero("USD"));
        assertThat(lc.tariffRateId()).isNull();
    }

    @Test
    void longestHsPrefixWinsOverWildcardsAndGenericRows() {
        TariffRate generic = rate("CN", "US", "", "0.03", "25.00", "0.003464", T2025, null);
        TariffRate chapter = rate("CN", "US", "84", "0.05", "25.00", "0.003464", T2025, null);
        TariffRate heading = rate("CN", "US", "8471.30", "0.25", "40.00", "0.003464", T2025, null);
        TariffRate anyOrigin = rate("*", "US", "847130", "0.10", "10.00", "0", T2025, null);

        LandedCost laptop = landed.estimate("CN", "US", "8471.30.01", Money.usd("1000.00"), T2026);
        assertThat(laptop.tariffRateId()).isEqualTo(heading.id());
        assertThat(laptop.duty()).isEqualTo(Money.usd("250.00"));
        assertThat(laptop.brokerage()).isEqualTo(Money.usd("40.00"));
        assertThat(laptop.surcharges()).isEqualTo(Money.usd("3.46"));
        assertThat(laptop.total()).isEqualTo(Money.usd("293.46"));

        LandedCost pump = landed.estimate("CN", "US", "8413.70", Money.usd("1000.00"), T2026);
        assertThat(pump.tariffRateId()).isEqualTo(chapter.id());
        assertThat(pump.duty()).isEqualTo(Money.usd("50.00"));

        LandedCost shoes = landed.estimate("CN", "US", "6403.99", Money.usd("1000.00"), T2026);
        assertThat(shoes.tariffRateId()).isEqualTo(generic.id());

        // From Vietnam only the wildcard-origin row matches the laptop heading.
        LandedCost vn = landed.estimate("VN", "US", "847130", Money.usd("1000.00"), T2026);
        assertThat(vn.tariffRateId()).isEqualTo(anyOrigin.id());
        assertThat(vn.duty()).isEqualTo(Money.usd("100.00"));
    }

    @Test
    void effectiveDatesSelectTheRowInForce() {
        TariffRate old = rate("MX", "US", "87", "0.025", "15.00", "0", T2025, T2026);
        TariffRate current = rate("MX", "US", "87", "0.25", "15.00", "0", T2026, null);
        assertThat(landed.estimate("MX", "US", "8708.29", Money.usd("200.00"), Instant.parse("2025-06-01T00:00:00Z")).tariffRateId()).isEqualTo(old.id());
        assertThat(landed.estimate("MX", "US", "8708.29", Money.usd("200.00"), Instant.parse("2026-06-01T00:00:00Z")).tariffRateId()).isEqualTo(current.id());
        // Exactly at the boundary the new row applies (from inclusive, to exclusive).
        assertThat(landed.estimate("MX", "US", "8708.29", Money.usd("200.00"), T2026).tariffRateId()).isEqualTo(current.id());
        // Before any row is effective there is no tariff: zero with no id so sourcing can flag the gap.
        LandedCost none = landed.estimate("MX", "US", "8708.29", Money.usd("200.00"), Instant.parse("2024-06-01T00:00:00Z"));
        assertThat(none.tariffRateId()).isNull();
        assertThat(none.total()).isEqualTo(Money.zero("USD"));
        assertThat(none.domestic()).isFalse();
    }

    @Test
    void upsertListAndDeleteAreTenantScoped() {
        TariffRate r = rate("DE", "US", "9018", "0.0", "30.00", "0.01", T2025, null);
        assertThat(tariffs.list("de", null, 0, 10).total()).isEqualTo(1);
        TariffRate updated = tariffs.upsert(new TariffRate(r.id(), "DE", "US", "9018.90", new BigDecimal("0.02"), new BigDecimal("30.00"), BigDecimal.ZERO, T2025, null, "ticket-1"));
        assertThat(updated.hsCodePrefix()).isEqualTo("901890");
        assertThat(updated.dutyRate()).isEqualByComparingTo("0.02");
        assertThat(tariffs.delete(r.id())).isEqualTo(1);
        assertThat(tariffs.list(null, null, 0, 10).total()).isZero();
    }
}
