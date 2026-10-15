package ai.intentoms.sourcing.economics;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.pnl.Pnl;
import ai.intentoms.sourcing.Fixtures;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SimulationOverrides;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.rates.RateCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FR-SRC-02 economics: shipping by weight/zone, labour/packaging/handling, split penalty, markdown risk, landed cost. */
class EconomicsCalculatorTest {
    final RateCard card = Fixtures.demoCard();
    final EconomicsCalculator econ = Fixtures.economics();
    final Node dc = Fixtures.dc("DC-NJ", 40.5187, -74.4121, "NJ", 1.10);

    @Test
    void packageCostsAddUpFromRateCardAndCostProfile() {
        SourcingRequest req = Fixtures.request("ground", "10001", "NY", List.of(Fixtures.line("L1", "A", 2, 50, 20), Fixtures.line("L2", "B", 1, 30, 10)));
        var allocs = List.of(new EconomicsCalculator.LineAlloc(req.lines().get(0), 2), new EconomicsCalculator.LineAlloc(req.lines().get(1), 1));
        var p = econ.packageEconomics(card, dc, allocs, req, 50.0);
        assertThat(p.shipping()).isEqualTo(Money.usd("8.10"));          // 1.5 kg → 2 kg band, zone 1
        assertThat(p.labour()).isEqualTo(Money.usd("3.30"));            // 1.10 × 3 units
        assertThat(p.packaging()).isEqualTo(Money.usd("0.65"));         // per package
        assertThat(p.handling()).isEqualTo(Money.usd("1.20"));          // 0.40 × 3
        assertThat(p.landed().isZero()).isTrue();
        assertThat(p.markdown().isZero()).isTrue();
        assertThat(p.total()).isEqualTo(Money.usd("13.25"));
        assertThat(p.quote().transitDays()).isEqualTo(1);
    }

    @Test
    void splitPenaltyAppliesPerExtraPackage() {
        assertThat(EconomicsCalculator.splitPenalty(12.0, 1, "USD").isZero()).isTrue();
        assertThat(EconomicsCalculator.splitPenalty(12.0, 3, "USD")).isEqualTo(Money.usd("24.00"));
        assertThat(EconomicsCalculator.splitPenalty(12.0, 0, "USD").isZero()).isTrue();
    }

    @Test
    void markdownRiskIsFractionOfLineValue() {
        SourcingRequest req = Fixtures.request("ground", "10001", "NY", List.of(Fixtures.line("L1", "A", 2, 50, 20, Map.of("markdown_risk", 0.2))));
        var p = econ.packageEconomics(card, dc, List.of(new EconomicsCalculator.LineAlloc(req.lines().get(0), 2)), req, 50.0);
        assertThat(p.markdown()).isEqualTo(Money.usd("20.00"));         // 0.2 × 100
    }

    @Test
    void crossBorderNodeIncursLandedCostScaledBySimulatedTariffDelta() {
        SourcingRequest req = Fixtures.request("ground", "75201", "TX", List.of(Fixtures.line("L1", "A", 1, 200, 80, Map.of("hs_code", "6109.10"))));
        var allocs = List.of(new EconomicsCalculator.LineAlloc(req.lines().get(0), 1));
        var mx = econ.packageEconomics(card, Fixtures.vendorMx(), allocs, req, 900.0);
        assertThat(mx.landed()).isEqualTo(Money.usd("25.00"));          // 10% of 200 + 5 brokerage
        assertThat(mx.landedDetail().domestic()).isFalse();
        var domestic = econ.packageEconomics(card, dc, allocs, req, 900.0);
        assertThat(domestic.landed().isZero()).isTrue();
        assertThat(domestic.landedDetail()).isNull();

        var withTariff = SimulationContext.with(new SimulationOverrides(Set.of(), false, 0.5), () -> econ.packageEconomics(card, Fixtures.vendorMx(), allocs, req, 900.0));
        assertThat(withTariff.landed()).isEqualTo(Money.usd("37.50"));  // × 1.5
    }

    @Test
    void pnlFoldsEveryCostAndHonoursProrataDiscounts() {
        var l1 = new SourcingRequest.RequestLine("L1", "A", 2, Money.usd("50.00"), Money.usd("10.00"), Map.of("cogs", 20.0));
        SourcingRequest req = Fixtures.request("ground", "10001", "NY", List.of(l1));
        var allocs = List.of(new EconomicsCalculator.LineAlloc(l1, 1));   // half the line → half the discount
        var p = econ.packageEconomics(card, dc, allocs, req, 50.0);
        Pnl pnl = econ.pnl(allocs, Money.usd("4.99"), List.of(p), Money.usd("12.00"));
        assertThat(pnl.revenue()).isEqualByComparingTo("54.99");        // 50 + 4.99 shipping revenue
        assertThat(pnl.discounts()).isEqualByComparingTo("5.00");
        assertThat(pnl.cogs()).isEqualByComparingTo("20.00");
        assertThat(pnl.shipping()).isEqualByComparingTo(p.shipping().amount().add(new java.math.BigDecimal("12.00")));
        assertThat(pnl.labour()).isEqualByComparingTo(p.labour().amount().add(p.handling().amount()));
        assertThat(pnl.packaging()).isEqualByComparingTo(p.packaging().amount());
        assertThat(pnl.marginRatio()).isCloseTo(pnl.contributionMargin().doubleValue() / 49.99, within(0.001));
        assertThat(pnl.meetsMarginFloor(0.10)).isTrue();          // 5.39 / 49.99 ≈ 0.108
        assertThat(pnl.meetsMarginFloor(0.12)).isFalse();
    }
}
