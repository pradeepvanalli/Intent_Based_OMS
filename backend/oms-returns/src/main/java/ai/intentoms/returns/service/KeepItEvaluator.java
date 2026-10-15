package ai.intentoms.returns.service;

import ai.intentoms.returns.model.ItemCondition;
import ai.intentoms.returns.model.KeepIt;
import ai.intentoms.returns.model.ReturnLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Keep-it economics (FR-RET-05): offer a returnless refund when the cost of getting the goods back (label + handling)
 * exceeds the resale recovery (unit price × recovery rate, assuming new condition) and abuse risk is low (score &lt; 1).
 * Hazmat lines are never kept (they must not stay unaccounted) and digital lines are never evaluated.
 */
public final class KeepItEvaluator {
    public static final double LOW_RISK_BELOW = 1.0;
    private KeepItEvaluator() {}

    /**
     * @param lines        returned lines
     * @param riskScore    the abuse-risk score (null = unknown → not offered)
     * @param shippingUsd  estimated return label cost
     * @param handlingUsd  estimated receiving/inspection cost
     * @param recoveryRate fraction of the unit price recovered on resale for condition {@code new}
     * @param enabled      tenant master switch
     */
    public static KeepIt evaluate(List<ReturnLine> lines, Double riskScore, double shippingUsd, double handlingUsd, double recoveryRate, boolean enabled) {
        BigDecimal cost = BigDecimal.valueOf(shippingUsd + handlingUsd).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal recovery = recoveryValue(lines, recoveryRate, ItemCondition.NEW);
        if (!enabled) return new KeepIt(false, "disabled", cost, recovery);
        if (lines.stream().anyMatch(l -> l.flag("hazmat"))) return new KeepIt(false, "hazmat", cost, recovery);
        if (riskScore == null) return new KeepIt(false, "risk_unknown", cost, recovery);
        if (riskScore >= LOW_RISK_BELOW) return new KeepIt(false, "risk_too_high", cost, recovery);
        if (cost.compareTo(recovery) > 0) return new KeepIt(true, "return_cost_exceeds_recovery", cost, recovery);
        return new KeepIt(false, "recovery_exceeds_cost", cost, recovery);
    }

    /** Expected resale recovery of the returned units for a condition: new 100 %, like_new 75 %, damaged 40 %, otherwise 0 of the base rate. */
    public static BigDecimal recoveryValue(List<ReturnLine> lines, double recoveryRate, String condition) {
        double factor = switch (condition == null ? ItemCondition.NEW : condition) {
            case ItemCondition.NEW -> 1.0;
            case ItemCondition.LIKE_NEW -> 0.75;
            case ItemCondition.DAMAGED -> 0.4;
            default -> 0.0;
        };
        BigDecimal merch = BigDecimal.ZERO;
        for (ReturnLine l : lines) merch = merch.add(l.extended().amount());
        return merch.multiply(BigDecimal.valueOf(recoveryRate * factor)).setScale(2, RoundingMode.HALF_EVEN);
    }
}
