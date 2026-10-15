package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.*;
import ai.intentoms.returns.model.RefundInfo;
import ai.intentoms.returns.model.ReturnLine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-RET-04 refund arithmetic: merchandise, proportional tax share, shipping share, restocking fee. */
class RefundCalculatorTest {
    private static Order order(List<OrderLine> lines, Money shipping) {
        Instant now = Instant.now();
        List<Charge> charges = shipping == null ? List.of() : List.of(new Charge("shipping", "GROUND", shipping, "ship"));
        return new Order("o1", "web", "W1", "standard_ship", "c1", null, lines, List.of(), charges, List.of(), List.of(), List.of(), null, null, null, null,
            "order.standard_ship", 1, "delivered", "ground", "USD", Map.of(), now, now, now, 1);
    }

    private static OrderLine orderLine(String id, String price, int qty, String tax) {
        return new OrderLine(id, "SKU-" + id, "d", qty, Money.usd(price), Money.zero("USD"), Money.usd(tax), null, null, Map.of());
    }

    private static ReturnLine returned(OrderLine ol, int qty, String condition) {
        return new ReturnLine(ol.lineId(), ol.sku(), ol.description(), qty, ol.unitPrice(), RefundCalculator.taxShare(ol, qty), "defective", null, condition, null, Map.of());
    }

    @Test
    void taxShareIsProportionalToReturnedQuantity() {
        OrderLine ol = orderLine("L1", "30.00", 3, "6.00");
        assertThat(RefundCalculator.taxShare(ol, 1)).isEqualTo(Money.usd("2.00"));
        assertThat(RefundCalculator.taxShare(ol, 3)).isEqualTo(Money.usd("6.00"));
        assertThat(RefundCalculator.taxShare(orderLine("L2", "10.00", 2, "0.00"), 1)).isEqualTo(Money.zero("USD"));
    }

    @Test
    void merchandisePlusTaxWithoutFeeOrShipping() {
        OrderLine ol = orderLine("L1", "30.00", 3, "6.00");
        RefundInfo r = RefundCalculator.compute(List.of(returned(ol, 2, null)), order(List.of(ol), Money.usd("9.00")), 0.15, false, false, RefundInfo.ORIGINAL_TENDER);
        assertThat(r.amount()).isEqualByComparingTo("64.00");       // 60 merchandise + 4 tax
        assertThat(r.taxRefunded()).isEqualByComparingTo("4.00");
        assertThat(r.restockingFee()).isEqualByComparingTo("0.00");
        assertThat(r.shippingRefunded()).isEqualByComparingTo("0.00");
        assertThat(r.status()).isEqualTo(RefundInfo.PENDING);
        assertThat(r.method()).isEqualTo(RefundInfo.ORIGINAL_TENDER);
    }

    @Test
    void restockingFeeAppliesOnlyWhenTheConditionCallsForIt() {
        OrderLine ol = orderLine("L1", "100.00", 1, "8.00");
        RefundInfo damaged = RefundCalculator.compute(List.of(returned(ol, 1, "damaged")), order(List.of(ol), null), 0.15, true, false, RefundInfo.ORIGINAL_TENDER);
        assertThat(damaged.restockingFee()).isEqualByComparingTo("15.00");
        assertThat(damaged.amount()).isEqualByComparingTo("93.00");   // 100 + 8 - 15
        RefundInfo fresh = RefundCalculator.compute(List.of(returned(ol, 1, "new")), order(List.of(ol), null), 0.15, false, false, RefundInfo.ORIGINAL_TENDER);
        assertThat(fresh.restockingFee()).isEqualByComparingTo("0.00");
        assertThat(fresh.amount()).isEqualByComparingTo("108.00");
    }

    @Test
    void shippingIsRefundedInProportionToMerchandiseReturned() {
        OrderLine a = orderLine("L1", "60.00", 1, "0.00");
        OrderLine b = orderLine("L2", "40.00", 1, "0.00");
        Order o = order(List.of(a, b), Money.usd("10.00"));
        RefundInfo partial = RefundCalculator.compute(List.of(returned(a, 1, null)), o, 0, false, true, RefundInfo.ORIGINAL_TENDER);
        assertThat(partial.shippingRefunded()).isEqualByComparingTo("6.00");
        assertThat(partial.amount()).isEqualByComparingTo("66.00");
        RefundInfo full = RefundCalculator.compute(List.of(returned(a, 1, null), returned(b, 1, null)), o, 0, false, true, RefundInfo.STORE_CREDIT);
        assertThat(full.shippingRefunded()).isEqualByComparingTo("10.00");
        assertThat(full.amount()).isEqualByComparingTo("110.00");
        assertThat(full.method()).isEqualTo(RefundInfo.STORE_CREDIT);
    }

    @Test
    void feeCanNeverPushTheRefundBelowZero() {
        OrderLine ol = orderLine("L1", "10.00", 1, "0.00");
        RefundInfo r = RefundCalculator.compute(List.of(returned(ol, 1, "damaged")), order(List.of(ol), null), 1.5, true, false, RefundInfo.ORIGINAL_TENDER);
        assertThat(r.amount()).isEqualByComparingTo("0.00");
    }
}
