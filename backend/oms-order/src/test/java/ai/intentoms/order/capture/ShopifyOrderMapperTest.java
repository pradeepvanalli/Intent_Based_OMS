package ai.intentoms.order.capture;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-CAP-01: Shopify orders/create payload → CaptureRequest. */
public class ShopifyOrderMapperTest {
    public static final String PAYLOAD = """
        {"id": 5551234, "name": "#1001", "email": "jane@example.com", "currency": "USD", "created_at": "2026-10-01T10:15:00-07:00",
         "total_price": "95.00", "total_discounts": "8.00", "financial_status": "paid", "payment_gateway_names": ["shopify_payments"], "note": "Leave at the side door please",
         "note_attributes": [{"name": "gift_wrap", "value": "yes"}],
         "customer": {"id": 42, "first_name": "Jane", "last_name": "Doe", "orders_count": 7, "phone": "+14155550100"},
         "line_items": [{"id": 1, "sku": "TEE-BLK-M", "quantity": 2, "price": "28.00", "title": "Organic cotton tee", "total_discount": "5.00"},
                        {"id": 2, "sku": "CAP-NVY", "quantity": 1, "price": "32.00", "title": "Wool cap"}],
         "shipping_lines": [{"code": "ground", "title": "Standard", "price": "6.00"}],
         "shipping_address": {"first_name": "Jane", "last_name": "Doe", "address1": "1 Market St", "address2": "Apt 4", "city": "San Francisco", "province_code": "CA", "zip": "94105", "country_code": "US", "phone": "+14155550100"},
         "billing_address": {"name": "Jane Doe", "address1": "1 Market St", "city": "San Francisco", "province_code": "CA", "zip": "94105", "country_code": "US", "company": "Acme"}}
        """;

    @Test
    void mapsHeaderLinesAddressesChargesTendersAndNotes() {
        CaptureRequest r = ShopifyOrderMapper.map(Json.readMap(PAYLOAD));
        assertThat(r.channel()).isEqualTo("shopify");
        assertThat(r.channelOrderId()).isEqualTo("5551234");
        assertThat(r.effectiveOrderType()).isEqualTo("standard_ship");
        assertThat(r.customerRef()).isEqualTo("shopify:42");
        assertThat(r.customer().email()).isEqualTo("jane@example.com");
        assertThat(r.customer().name()).isEqualTo("Jane Doe");
        assertThat(r.customer().history()).containsEntry("orders_count", 7);
        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(0).lineId()).isEqualTo("1");
        assertThat(r.lines().get(0).sku()).isEqualTo("TEE-BLK-M");
        assertThat(r.lines().get(0).qty()).isEqualTo(2);
        assertThat(r.lines().get(0).unitPrice()).isEqualTo(Money.usd("28.00"));
        assertThat(r.lines().get(0).discount()).isEqualTo(Money.usd("5.00"));
        assertThat(r.lines().get(0).description()).isEqualTo("Organic cotton tee");
        assertThat(r.shipTo().line1()).isEqualTo("1 Market St");
        assertThat(r.shipTo().state()).isEqualTo("CA");
        assertThat(r.shipTo().zip()).isEqualTo("94105");
        assertThat(r.shipTo().name()).isEqualTo("Jane Doe");
        assertThat(r.address("bill_to").addressType()).isEqualTo("commercial");
        assertThat(r.shipMethod()).isEqualTo("ground");
        assertThat(r.charges()).extracting(CaptureRequest.ChargeInput::type).containsExactly("shipping", "discount");
        assertThat(r.charges().get(0).amount()).isEqualTo(Money.usd("6.00"));
        assertThat(r.charges().get(1).amount()).isEqualTo(Money.usd("-3.00")); // 8.00 total minus the 5.00 already on the line
        assertThat(r.tenders()).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("card");
            assertThat(t.amount()).isEqualTo(Money.usd("95.00"));
            assertThat(t.token()).isEqualTo("shopify:5551234:shopify_payments");
            assertThat(t.details()).containsEntry("financial_status", "paid");
        });
        assertThat(r.notes()).singleElement().satisfies(n -> { assertThat(n.source()).isEqualTo("customer"); assertThat(n.text()).contains("side door"); });
        assertThat(r.extensions()).containsEntry("gift_wrap", "yes").containsEntry("shopify_order_name", "#1001");
        assertThat(r.placedAt()).isEqualTo(Instant.parse("2026-10-01T17:15:00Z"));
    }

    @Test
    void fallsBackToNameAndDefaultsWhenFieldsMissing() {
        CaptureRequest r = ShopifyOrderMapper.map(Map.of("name", "#2002", "line_items", java.util.List.of(Map.of("sku", "CAP-NVY", "quantity", 1, "price", "32.00")), "payment_gateway_names", java.util.List.of("paypal"), "total_price", "32.00"));
        assertThat(r.channelOrderId()).isEqualTo("#2002");
        assertThat(r.lines().get(0).lineId()).isEqualTo("L1");
        assertThat(r.tenders().get(0).type()).isEqualTo("paypal");
        assertThat(r.addresses()).isEmpty();
        assertThat(r.effectiveCurrency()).isEqualTo("USD");
    }
}
