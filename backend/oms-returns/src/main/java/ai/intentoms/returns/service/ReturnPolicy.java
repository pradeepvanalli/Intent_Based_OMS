package ai.intentoms.returns.service;

import ai.intentoms.returns.model.Eligibility;
import ai.intentoms.returns.model.ReturnLine;

import java.util.ArrayList;
import java.util.List;

/**
 * L0 return policy (FR-RET-02): the deterministic checks that run before (and are mirrored by the L0 rules of) the
 * {@code return_risk} decision. Pure so it can be unit-tested exhaustively.
 * <ul>
 *   <li>{@code out_of_window}: days since delivery exceed the order type's return window;</li>
 *   <li>{@code final_sale}: any returned line carries the {@code final_sale} attribute;</li>
 *   <li>{@code digital_goods}: any returned line is digital and therefore not returnable.</li>
 * </ul>
 * A non-eligible outcome is a <em>recommendation</em> to deny (T3): the service routes it to a human (GR-16: denials carry reason codes).
 */
public final class ReturnPolicy {
    public static final String OUT_OF_WINDOW = "out_of_window";
    public static final String FINAL_SALE = "final_sale";
    public static final String DIGITAL_GOODS = "digital_goods";

    private ReturnPolicy() {}

    public static Eligibility evaluate(List<ReturnLine> lines, int windowDays, Integer daysSinceDelivery) {
        List<String> reasons = new ArrayList<>();
        boolean finalSale = lines.stream().anyMatch(l -> l.flag("final_sale"));
        boolean digital = lines.stream().anyMatch(l -> l.flag("digital"));
        if (daysSinceDelivery != null && daysSinceDelivery > windowDays) reasons.add(OUT_OF_WINDOW);
        if (finalSale) reasons.add(FINAL_SALE);
        if (digital) reasons.add(DIGITAL_GOODS);
        return new Eligibility(reasons.isEmpty(), reasons, windowDays, daysSinceDelivery, finalSale);
    }

    /** Whether a {@code return_risk} L0 value is a denial ({@code deny_out_of_window}, {@code deny_final_sale}, ...). */
    public static boolean isDenial(Object decisionValue) {
        return decisionValue != null && decisionValue.toString().startsWith("deny_");
    }

    /** {@code deny_out_of_window} → {@code out_of_window}. */
    public static String denialReason(Object decisionValue) {
        String v = String.valueOf(decisionValue);
        return v.startsWith("deny_") ? v.substring(5) : v;
    }
}
