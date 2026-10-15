package ai.intentoms.returns.model;

import java.util.List;

/**
 * L0 policy outcome (FR-RET-02): return window, final-sale and digital-goods checks. {@code reasons} carries reason
 * codes (GR-16: denials always carry reason codes), e.g. {@code out_of_window}, {@code final_sale}, {@code digital_goods}.
 */
public record Eligibility(boolean eligible, List<String> reasons, Integer windowDays, Integer daysSinceDelivery, boolean finalSale) {
    public Eligibility {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public Eligibility withEligible(boolean e, List<String> newReasons) { return new Eligibility(e, newReasons, windowDays, daysSinceDelivery, finalSale); }
}
