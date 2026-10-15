package ai.intentoms.finance.landedcost;

import ai.intentoms.finance.model.Money;

/**
 * Landed cost estimate for one cross-border shipment leg (FR-FIN-05).
 *
 * @param duty         customs duty (value × duty rate)
 * @param brokerage    flat brokerage fee
 * @param surcharges   value × surcharge rate
 * @param total        duty + brokerage + surcharges
 * @param tariffRateId tariff row applied, or null when none matched / domestic
 * @param domestic     true when origin and destination are the same country (everything zero)
 */
public record LandedCost(Money duty, Money brokerage, Money surcharges, Money total, String tariffRateId, boolean domestic) {
    public static LandedCost zero(String currency, boolean domestic) {
        Money z = Money.zero(currency);
        return new LandedCost(z, z, z, z, null, domestic);
    }
}
