package ai.intentoms.order.validation;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.model.OrderType;

import java.util.List;
import java.util.Map;

/** Builders shared by the unit tests. */
public final class TestRequests {
    private TestRequests() {}

    public static final OrderType STANDARD = new OrderType("standard_ship", "d", "order.standard_ship", "order_line.standard_ship", "order.standard_ship", List.of(), Map.of("web", "ship_as_available"), 30, Map.of());
    public static final CatalogItem TEE = new CatalogItem("TEE-BLK-M", "Tee", Map.of("price", 28.00, "cogs", 7.50, "weightKg", 0.25));
    public static final CatalogItem PERFUME = new CatalogItem("PERF-50ML", "Parfum", Map.of("price", 120.00, "cogs", 22.0, "hazmat", true, "hazmatClass", "3 flammable liquid"));
    public static final CatalogItem GIFT_CARD = new CatalogItem("GIFT-CARD", "Gift card", Map.of("price", 50.00, "cogs", 0.0, "digital", true));

    public static CaptureRequest.LineInput line(String id, String sku, int qty, String price) {
        return new CaptureRequest.LineInput(id, sku, null, qty, Money.usd(price), null, null, null);
    }

    public static CaptureRequest.AddressInput shipTo(String line1, String city, String state, String zip) {
        return new CaptureRequest.AddressInput("ship_to", "Jane Doe", line1, null, city, state, zip, "US", null, null);
    }

    public static CaptureRequest.TenderInput card(String amount) { return new CaptureRequest.TenderInput("card", "tok_visa", Money.usd(amount), null); }

    public static CaptureRequest request(List<CaptureRequest.LineInput> lines, List<CaptureRequest.AddressInput> addresses, List<CaptureRequest.TenderInput> tenders) {
        return new CaptureRequest("web", "W-1", "standard_ship", "cust-1", new CaptureRequest.CustomerInput("cust-1", "Jane Doe", "jane@example.com", null, "gold", false, null, Map.of()),
            lines, addresses, List.of(), tenders, List.of(), List.of(), "ground", null, "USD", Map.of(), null);
    }

    public static CaptureRequest simple() {
        return request(List.of(line("L1", "TEE-BLK-M", 2, "28.00")), List.of(shipTo("1 Market St", "San Francisco", "CA", "94105")), List.of(card("100.00")));
    }

    public static ValidationContext ctx(CaptureRequest req, Map<String, CatalogItem> catalog) {
        return new ValidationContext(STANDARD, catalog, req.merchandiseTotal().add(req.chargeTotal()), false, 0.25, null);
    }

    public static Map<String, CatalogItem> catalog() { return Map.of(TEE.sku(), TEE, PERFUME.sku(), PERFUME, GIFT_CARD.sku(), GIFT_CARD); }
}
