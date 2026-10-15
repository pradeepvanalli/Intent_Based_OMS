package ai.intentoms.order.capture;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps a Shopify {@code orders/create} webhook payload onto a {@link CaptureRequest} (FR-CAP-01 storefront connector).
 * Pure function: {@code id}/{@code name} → channelOrderId, {@code email} + {@code customer} → customer, {@code line_items}
 * → lines (sku, quantity, price, title, total_discount), {@code shipping_address} / {@code billing_address} → addresses,
 * {@code shipping_lines} → shipping charge, {@code total_discounts} beyond the line discounts → order-level discount charge, {@code payment_gateway_names} + {@code total_price} → tenders,
 * {@code note} → a customer note, {@code shipping_lines[0].code} → shipMethod, {@code note_attributes} → extensions.
 */
public final class ShopifyOrderMapper {
    public static final String CHANNEL = "shopify";

    private ShopifyOrderMapper() {}

    @SuppressWarnings("unchecked")
    public static CaptureRequest map(Map<String, Object> shop) {
        if (shop == null || shop.isEmpty()) throw new ValidationException("Empty Shopify payload");
        Object id = shop.get("id");
        String channelOrderId = id != null ? String.valueOf(id) : str(shop.get("name"));
        if (channelOrderId == null) throw new ValidationException("Shopify payload needs an id or name");
        String currency = shop.get("currency") != null ? String.valueOf(shop.get("currency")) : "USD";

        Map<String, Object> cust = shop.get("customer") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String email = str(shop.get("email")) != null ? str(shop.get("email")) : str(cust.get("email"));
        String name = joinName(str(cust.get("first_name")), str(cust.get("last_name")));
        String customerRef = cust.get("id") != null ? "shopify:" + cust.get("id") : email;
        Integer ordersCount = cust.get("orders_count") instanceof Number n ? n.intValue() : null;
        Map<String, Object> history = ordersCount == null ? Map.of() : Map.of("orders_count", ordersCount);
        CaptureRequest.CustomerInput customer = new CaptureRequest.CustomerInput(customerRef, name, email, str(cust.get("phone")), str(cust.get("tags")), false, null, history);

        List<CaptureRequest.LineInput> lines = new ArrayList<>();
        int i = 0;
        for (Object o : list(shop.get("line_items"))) {
            if (!(o instanceof Map<?, ?> lm)) continue;
            Map<String, Object> li = (Map<String, Object>) lm;
            i++;
            String lineId = li.get("id") != null ? String.valueOf(li.get("id")) : "L" + i;
            String sku = str(li.get("sku"));
            int qty = li.get("quantity") instanceof Number n ? n.intValue() : 1;
            Money price = money(li.get("price"), currency);
            Money discount = li.get("total_discount") != null ? money(li.get("total_discount"), currency) : null;
            lines.add(new CaptureRequest.LineInput(lineId, sku, str(li.get("title")), qty, price, discount, null, null));
        }

        List<CaptureRequest.AddressInput> addresses = new ArrayList<>();
        if (shop.get("shipping_address") instanceof Map<?, ?> a) addresses.add(address("ship_to", (Map<String, Object>) a));
        if (shop.get("billing_address") instanceof Map<?, ?> a) addresses.add(address("bill_to", (Map<String, Object>) a));

        List<CaptureRequest.ChargeInput> charges = new ArrayList<>();
        String shipMethod = null;
        for (Object o : list(shop.get("shipping_lines"))) {
            if (!(o instanceof Map<?, ?> sm)) continue;
            Map<String, Object> sl = (Map<String, Object>) sm;
            if (shipMethod == null) shipMethod = str(sl.get("code")) != null ? str(sl.get("code")) : str(sl.get("title"));
            if (sl.get("price") != null) charges.add(new CaptureRequest.ChargeInput("shipping", str(sl.get("code")), money(sl.get("price"), currency), str(sl.get("title"))));
        }
        if (shop.get("total_discounts") != null) {
            // total_discounts already includes the per-line total_discount values; only the remainder is an order-level discount.
            Money lineDiscounts = Money.zero(currency);
            for (CaptureRequest.LineInput li : lines) if (li.discount() != null) lineDiscounts = lineDiscounts.add(li.discount());
            Money headerDiscount = money(shop.get("total_discounts"), currency).subtract(lineDiscounts);
            if (headerDiscount.isPositive()) charges.add(new CaptureRequest.ChargeInput("discount", "shopify_discount", headerDiscount.negate(), "Order-level discounts"));
        }

        List<CaptureRequest.TenderInput> tenders = new ArrayList<>();
        Money total = shop.get("total_price") != null ? money(shop.get("total_price"), currency) : null;
        List<Object> gateways = list(shop.get("payment_gateway_names"));
        if (total != null) {
            String gateway = gateways.isEmpty() ? "shopify_payments" : String.valueOf(gateways.get(0));
            tenders.add(new CaptureRequest.TenderInput(tenderType(gateway), "shopify:" + channelOrderId + ":" + gateway, total, Map.of("gateway", gateway, "financial_status", String.valueOf(shop.get("financial_status")))));
        }

        List<CaptureRequest.NoteInput> notes = new ArrayList<>();
        if (str(shop.get("note")) != null) notes.add(new CaptureRequest.NoteInput("customer", email, str(shop.get("note")), false));

        Map<String, Object> extensions = new java.util.LinkedHashMap<>();
        for (Object o : list(shop.get("note_attributes"))) {
            if (o instanceof Map<?, ?> na && na.get("name") != null) extensions.put(String.valueOf(na.get("name")), na.get("value"));
        }
        if (shop.get("source_name") != null) extensions.put("shopify_source", shop.get("source_name"));
        if (shop.get("name") != null) extensions.put("shopify_order_name", shop.get("name"));

        Instant placedAt = instant(shop.get("created_at"));
        return new CaptureRequest(CHANNEL, channelOrderId, CaptureRequest.DEFAULT_ORDER_TYPE, customerRef, customer, lines, addresses, charges, tenders, notes,
            List.of(), shipMethod, placedAt, currency, extensions, null);
    }

    private static CaptureRequest.AddressInput address(String type, Map<String, Object> a) {
        String name = str(a.get("name")) != null ? str(a.get("name")) : joinName(str(a.get("first_name")), str(a.get("last_name")));
        return new CaptureRequest.AddressInput(type, name, str(a.get("address1")), str(a.get("address2")), str(a.get("city")), str(a.get("province_code")), str(a.get("zip")),
            str(a.get("country_code")) != null ? str(a.get("country_code")) : "US", str(a.get("phone")), str(a.get("company")) != null ? "commercial" : null);
    }

    static String tenderType(String gateway) {
        String g = gateway == null ? "" : gateway.toLowerCase();
        if (g.contains("paypal")) return "paypal";
        if (g.contains("gift")) return "gift_card";
        return "card";
    }

    private static Money money(Object v, String currency) {
        if (v == null) return Money.zero(currency);
        try { return new Money(new BigDecimal(String.valueOf(v)), currency); }
        catch (NumberFormatException e) { throw new ValidationException("Bad Shopify amount '" + v + "'"); }
    }

    private static Instant instant(Object v) {
        if (v == null) return null;
        try { return java.time.OffsetDateTime.parse(String.valueOf(v)).toInstant(); }
        catch (DateTimeParseException e) { return null; }
    }

    private static String joinName(String first, String last) {
        String n = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return n.isEmpty() ? null : n;
    }

    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
}
