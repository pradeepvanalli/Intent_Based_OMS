package ai.intentoms.finance.landedcost;

import ai.intentoms.finance.model.Money;

import java.time.Instant;

/**
 * Landed cost per sourcing option for cross-border nodes (FR-FIN-05): duty, brokerage and surcharges from the
 * tenant's tariff table with effective dates. Same-country legs cost zero.
 */
public interface LandedCostService {
    /**
     * @param originCountry ship-from country (ISO alpha-2)
     * @param destCountry   ship-to country
     * @param hsCode        harmonised system code of the goods (digits, dots allowed)
     * @param value         customs value
     * @param at            the instant the rates must be effective at (usually the promised ship date)
     */
    LandedCost estimate(String originCountry, String destCountry, String hsCode, Money value, Instant at);
}
