package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.returns.model.Eligibility;
import ai.intentoms.returns.model.ReturnLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-RET-02 L0 policy: window, final sale, digital goods (GR-16: denials carry reason codes). */
class ReturnPolicyTest {
    private static ReturnLine line(Map<String, Object> attrs) {
        return new ReturnLine("L1", "SKU", "d", 1, Money.usd("20.00"), Money.zero("USD"), "changed_mind", null, null, null, attrs);
    }

    @Test
    void insideWindowPlainItemIsEligible() {
        Eligibility e = ReturnPolicy.evaluate(List.of(line(Map.of())), 30, 12);
        assertThat(e.eligible()).isTrue();
        assertThat(e.reasons()).isEmpty();
        assertThat(e.windowDays()).isEqualTo(30);
        assertThat(e.daysSinceDelivery()).isEqualTo(12);
    }

    @Test
    void lastDayOfWindowIsStillEligibleAndDayAfterIsNot() {
        assertThat(ReturnPolicy.evaluate(List.of(line(Map.of())), 30, 30).eligible()).isTrue();
        Eligibility late = ReturnPolicy.evaluate(List.of(line(Map.of())), 30, 31);
        assertThat(late.eligible()).isFalse();
        assertThat(late.reasons()).containsExactly(ReturnPolicy.OUT_OF_WINDOW);
    }

    @Test
    void finalSaleAndDigitalAreDeniedWithReasonCodes() {
        Eligibility fs = ReturnPolicy.evaluate(List.of(line(Map.of("final_sale", true))), 30, 2);
        assertThat(fs.eligible()).isFalse();
        assertThat(fs.finalSale()).isTrue();
        assertThat(fs.reasons()).containsExactly(ReturnPolicy.FINAL_SALE);
        Eligibility digital = ReturnPolicy.evaluate(List.of(line(Map.of("digital", "true"))), 30, 2);
        assertThat(digital.eligible()).isFalse();
        assertThat(digital.reasons()).containsExactly(ReturnPolicy.DIGITAL_GOODS);
        Eligibility all = ReturnPolicy.evaluate(List.of(line(Map.of("final_sale", true)), line(Map.of("digital", true))), 14, 40);
        assertThat(all.reasons()).containsExactly(ReturnPolicy.OUT_OF_WINDOW, ReturnPolicy.FINAL_SALE, ReturnPolicy.DIGITAL_GOODS);
    }

    @Test
    void unknownDeliveryDateDoesNotFailTheWindowCheck() {
        assertThat(ReturnPolicy.evaluate(List.of(line(Map.of())), 30, null).eligible()).isTrue();
    }

    @Test
    void denialValuesFromTheRiskDecisionAreRecognised() {
        assertThat(ReturnPolicy.isDenial("deny_out_of_window")).isTrue();
        assertThat(ReturnPolicy.isDenial("deny_final_sale")).isTrue();
        assertThat(ReturnPolicy.isDenial(2.0)).isFalse();
        assertThat(ReturnPolicy.isDenial(null)).isFalse();
        assertThat(ReturnPolicy.denialReason("deny_final_sale")).isEqualTo("final_sale");
    }
}
