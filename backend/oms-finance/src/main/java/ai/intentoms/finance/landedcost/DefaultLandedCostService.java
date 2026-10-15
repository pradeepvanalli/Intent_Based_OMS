package ai.intentoms.finance.landedcost;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.observability.Metrics;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Table-driven {@link LandedCostService} (FR-FIN-05). Picks the most specific tariff row effective at {@code at}
 * (longest HS prefix, then exact country over wildcard, then latest effective_from). No matching row means no duty is
 * known for the lane: the estimate is zero with {@code tariffRateId == null} so sourcing can flag the gap.
 */
@Service
public class DefaultLandedCostService implements LandedCostService {
    private final TariffRateRepository repo;
    private final Metrics metrics;

    public DefaultLandedCostService(TariffRateRepository repo, Metrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
    }

    @Override
    public LandedCost estimate(String originCountry, String destCountry, String hsCode, Money value, Instant at) {
        String origin = originCountry == null ? "" : originCountry.trim().toUpperCase(Locale.ROOT);
        String dest = destCountry == null ? "" : destCountry.trim().toUpperCase(Locale.ROOT);
        if (origin.equals(dest)) return LandedCost.zero(value.currency(), true);
        return metrics.time("oms.finance.landed_cost.estimate", () -> {
            List<TariffRate> rows = repo.candidates(origin, dest, hsCode, at);
            if (rows.isEmpty()) {
                metrics.count("oms.finance.landed_cost.no_tariff", "origin", origin, "dest", dest);
                return LandedCost.zero(value.currency(), false);
            }
            return apply(rows.get(0), value);
        });
    }

    /** Applies one tariff row to a customs value; pure. */
    public static LandedCost apply(TariffRate r, Money value) {
        Money base = value.isNegative() ? Money.zero(value.currency()) : value;
        Money duty = base.multiply(r.dutyRate());
        Money brokerage = new Money(r.brokerageFee(), value.currency());
        Money surcharges = base.multiply(r.surchargeRate());
        return new LandedCost(duty, brokerage, surcharges, duty.add(brokerage).add(surcharges), r.id(), false);
    }
}
