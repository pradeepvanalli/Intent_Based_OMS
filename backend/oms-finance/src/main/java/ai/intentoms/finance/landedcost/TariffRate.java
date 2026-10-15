package ai.intentoms.finance.landedcost;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the business-maintained tariff table (FR-FIN-05).
 *
 * @param id            row id ({@code trf_...}); null on create
 * @param originCountry ISO alpha-2 or {@code *} for any origin
 * @param destCountry   ISO alpha-2 or {@code *} for any destination
 * @param hsCodePrefix  HS code prefix (digits, dots ignored); empty string matches every code; longest prefix wins
 * @param dutyRate      duty as a fraction of customs value (0.065 = 6.5 %)
 * @param brokerageFee  flat brokerage fee per shipment in the order currency
 * @param surchargeRate additional surcharges (MPF, HMF, carrier) as a fraction of customs value
 * @param effectiveFrom first instant the row applies (inclusive)
 * @param effectiveTo   first instant the row no longer applies (exclusive); null = open-ended
 * @param note          free text (tariff act, ticket)
 */
public record TariffRate(
    String id,
    String originCountry,
    String destCountry,
    String hsCodePrefix,
    BigDecimal dutyRate,
    BigDecimal brokerageFee,
    BigDecimal surchargeRate,
    Instant effectiveFrom,
    Instant effectiveTo,
    String note
) {
    /** True when the row is in force at {@code at}. */
    public boolean effectiveAt(Instant at) {
        return !effectiveFrom.isAfter(at) && (effectiveTo == null || effectiveTo.isAfter(at));
    }
}
