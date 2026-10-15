package ai.intentoms.integrations.marketplace;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.platform.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping of marketplace order payloads onto {@link CaptureRequest} (FR-CAP-01 marketplace connectors; section 11
 * "Storefronts and marketplaces", P0 Shopify + one marketplace). Two shapes are accepted:
 * <ul>
 *   <li><b>Amazon SP-API Orders v0</b> (an {@code Order} with its {@code OrderItems} inlined, as the poller assembles it):
 *       {@code AmazonOrderId, PurchaseDate, OrderStatus, FulfillmentChannel, ShippingAddress{Name, AddressLine1, AddressLine2,
 *       City, StateOrRegion, PostalCode, CountryCode, Phone}, OrderTotal{CurrencyCode, Amount}, BuyerInfo{BuyerEmail, BuyerName},
 *       OrderItems[{ASIN, SellerSKU, QuantityOrdered, ItemPrice{Amount, CurrencyCode}, PromotionDiscount?, Title, OrderItemId}],
 *       IsPrime, ShipmentServiceLevelCategory}.</li>
 *   <li><b>Generic</b>: {@code {externalOrderId, placedAt, customer{ref,name,email,phone}, lines[{lineId?, sku, qty, unitPrice{amount,currency}|price,
 *       discount?, description?}], shipTo{name,line1,line2,city,state,zip,country,phone}, total{amount,currency}, shipMethod, orderType?, currency?}}.</li>
 * </ul>
 * Marketplaces settle with the seller later, so the tender is {@code net_terms} (deferred, tokenless — passes the
 * {@code payment_status} validation step) with {@code details.settlement = marketplace_settlement}; the marketplace's
 * own ids are preserved in {@code extensions} and the channel is {@code marketplace}. The mapper never invents facts:
 * missing sku, qty or price fail with a {@code validation_failed}.
 */
public final class MarketplaceOrderMapper {
    private MarketplaceOrderMapper() {}

    public static final String CHANNEL = "marketplace";
    public static final String TENDER_TYPE = "net_terms";
    public static final String SETTLEMENT = "marketplace_settlement";

    /** Detects the shape and maps it. */
    public static CaptureRequest map(String marketplace, Map<String, Object> body, String orderType) {
        if (body == null || body.isEmpty()) throw new ValidationException("Empty marketplace payload");
        if (body.containsKey("AmazonOrderId") || body.containsKey("OrderItems")) return mapAmazon(marketplace, body, orderType);
        if (body.containsKey("externalOrderId")) return mapGeneric(marketplace, body, orderType);
        throw new ValidationException("Unrecognised marketplace payload: expected AmazonOrderId (SP-API) or externalOrderId (generic)");
    }

    /** The marketplace order id that keys idempotency ({@code AmazonOrderId} or {@code externalOrderId}). */
    public static String externalOrderId(Map<String, Object> body) {
        Object id = body.get("AmazonOrderId") != null ? body.get("AmazonOrderId") : body.get("externalOrderId");
        if (id == null || String.valueOf(id).isBlank()) throw new ValidationException("Marketplace order id is required (AmazonOrderId or externalOrderId)");
        return String.valueOf(id).trim();
    }

    public static CaptureRequest mapAmazon(String marketplace, Map<String, Object> o, String orderType) {
        String amazonOrderId = externalOrderId(o);
        Map<String, Object> ship = map(o.get("ShippingAddress"));
        Map<String, Object> buyer = map(o.get("BuyerInfo"));
        Map<String, Object> total = map(o.get("OrderTotal"));
        String currency = str(total.get("CurrencyCode"), "USD");
        List<CaptureRequest.LineInput> lines = new ArrayList<>();
        int idx = 1;
        if (!(o.get("OrderItems") instanceof List<?> items) || items.isEmpty()) throw new ValidationException("OrderItems must contain at least one item");
        for (Object it : items) {
            Map<String, Object> item = map(it);
            String sku = str(item.get("SellerSKU"), null);
            if (sku == null) throw new ValidationException("OrderItems[" + (idx - 1) + "].SellerSKU is required");
            int qty = integer(item.get("QuantityOrdered"));
            if (qty <= 0) throw new ValidationException("OrderItems[" + (idx - 1) + "].QuantityOrdered must be positive");
            Map<String, Object> price = map(item.get("ItemPrice"));
            if (price.get("Amount") == null) throw new ValidationException("OrderItems[" + (idx - 1) + "].ItemPrice.Amount is required");
            // SP-API ItemPrice is the extended price for the whole quantity; OMS lines carry a unit price.
            Money extended = money(price.get("Amount"), str(price.get("CurrencyCode"), currency));
            Money unit = new Money(extended.amount().divide(BigDecimal.valueOf(qty), 2, Money.ROUNDING), extended.currency());
            Map<String, Object> promo = map(item.get("PromotionDiscount"));
            Money discount = promo.get("Amount") == null ? null : money(promo.get("Amount"), str(promo.get("CurrencyCode"), currency));
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (item.get("ASIN") != null) attrs.put("asin", item.get("ASIN"));
            if (item.get("OrderItemId") != null) attrs.put("marketplace_item_id", item.get("OrderItemId"));
            String lineId = item.get("OrderItemId") != null ? String.valueOf(item.get("OrderItemId")) : String.valueOf(idx);
            lines.add(new CaptureRequest.LineInput(lineId, sku, str(item.get("Title"), null), qty, unit, discount, null, attrs));
            idx++;
        }
        List<CaptureRequest.AddressInput> addresses = new ArrayList<>();
        if (!ship.isEmpty()) {
            addresses.add(new CaptureRequest.AddressInput("ship_to", str(ship.get("Name"), null), str(ship.get("AddressLine1"), null), str(ship.get("AddressLine2"), null),
                str(ship.get("City"), null), str(ship.get("StateOrRegion"), null), str(ship.get("PostalCode"), null), str(ship.get("CountryCode"), "US"), str(ship.get("Phone"), null),
                addressType(ship.get("AddressType"))));
        }
        String email = str(buyer.get("BuyerEmail"), null);
        String name = str(buyer.get("BuyerName"), str(ship.get("Name"), null));
        String customerRef = email != null ? marketplace + ":" + email.toLowerCase(Locale.ROOT) : marketplace + ":" + amazonOrderId;
        CaptureRequest.CustomerInput customer = new CaptureRequest.CustomerInput(customerRef, name, email, str(ship.get("Phone"), null),
            Boolean.TRUE.equals(o.get("IsPrime")) ? "prime" : null, null, null, Map.of());
        Money orderTotal = total.get("Amount") == null ? sum(lines, currency) : money(total.get("Amount"), currency);
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("marketplace", marketplace);
        extensions.put("marketplace_order_id", amazonOrderId);
        putIf(extensions, "marketplace_status", o.get("OrderStatus"));
        putIf(extensions, "fulfillment_channel", o.get("FulfillmentChannel"));
        putIf(extensions, "is_prime", o.get("IsPrime"));
        putIf(extensions, "service_level", o.get("ShipmentServiceLevelCategory"));
        putIf(extensions, "sales_channel", o.get("SalesChannel"));
        return new CaptureRequest(CHANNEL, marketplace + ":" + amazonOrderId, orderType, customerRef, customer, lines, addresses, List.of(),
            List.of(tender(marketplace, amazonOrderId, orderTotal)), List.of(), List.of(), shipMethod(o.get("ShipmentServiceLevelCategory")),
            instant(o.get("PurchaseDate")), currency, extensions, null);
    }

    public static CaptureRequest mapGeneric(String marketplace, Map<String, Object> o, String orderType) {
        String externalId = externalOrderId(o);
        Map<String, Object> cust = map(o.get("customer"));
        Map<String, Object> ship = map(o.get("shipTo"));
        Map<String, Object> total = map(o.get("total"));
        String currency = str(o.get("currency"), str(total.get("currency"), "USD"));
        if (!(o.get("lines") instanceof List<?> items) || items.isEmpty()) throw new ValidationException("lines must contain at least one line");
        List<CaptureRequest.LineInput> lines = new ArrayList<>();
        int idx = 1;
        for (Object it : items) {
            Map<String, Object> l = map(it);
            String sku = str(l.get("sku"), null);
            if (sku == null) throw new ValidationException("lines[" + (idx - 1) + "].sku is required");
            int qty = integer(l.get("qty"));
            if (qty <= 0) throw new ValidationException("lines[" + (idx - 1) + "].qty must be positive");
            Money unit;
            if (l.get("unitPrice") instanceof Map<?, ?> up) unit = money(((Map<?, ?>) up).get("amount"), str(((Map<?, ?>) up).get("currency"), currency));
            else if (l.get("price") != null) unit = money(l.get("price"), currency);
            else throw new ValidationException("lines[" + (idx - 1) + "] needs unitPrice{amount,currency} or price");
            Money discount = l.get("discount") instanceof Map<?, ?> d ? money(((Map<?, ?>) d).get("amount"), str(((Map<?, ?>) d).get("currency"), currency))
                : l.get("discount") != null ? money(l.get("discount"), currency) : null;
            Map<String, Object> attrs = l.get("attributes") instanceof Map<?, ?> a ? new LinkedHashMap<>(map(a)) : new LinkedHashMap<>();
            lines.add(new CaptureRequest.LineInput(str(l.get("lineId"), String.valueOf(idx)), sku, str(l.get("description"), null), qty, unit, discount, str(l.get("taxCode"), null), attrs));
            idx++;
        }
        List<CaptureRequest.AddressInput> addresses = new ArrayList<>();
        if (!ship.isEmpty()) {
            addresses.add(new CaptureRequest.AddressInput("ship_to", str(ship.get("name"), str(cust.get("name"), null)), str(ship.get("line1"), null), str(ship.get("line2"), null),
                str(ship.get("city"), null), str(ship.get("state"), null), str(ship.get("zip"), str(ship.get("postalCode"), null)), str(ship.get("country"), "US"),
                str(ship.get("phone"), null), addressType(ship.get("addressType"))));
        }
        String email = str(cust.get("email"), null);
        String customerRef = str(cust.get("ref"), email != null ? marketplace + ":" + email.toLowerCase(Locale.ROOT) : marketplace + ":" + externalId);
        CaptureRequest.CustomerInput customer = new CaptureRequest.CustomerInput(customerRef, str(cust.get("name"), null), email, str(cust.get("phone"), null), str(cust.get("tier"), null), null, null, Map.of());
        Money orderTotal = total.get("amount") == null ? sum(lines, currency) : money(total.get("amount"), currency);
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("marketplace", marketplace);
        extensions.put("marketplace_order_id", externalId);
        if (o.get("extensions") instanceof Map<?, ?> ext) map(ext).forEach(extensions::putIfAbsent);
        return new CaptureRequest(CHANNEL, marketplace + ":" + externalId, str(o.get("orderType"), orderType), customerRef, customer, lines, addresses, List.of(),
            List.of(tender(marketplace, externalId, orderTotal)), List.of(), List.of(), str(o.get("shipMethod"), "ground"), instant(o.get("placedAt")), currency, extensions, null);
    }

    static CaptureRequest.TenderInput tender(String marketplace, String externalId, Money total) {
        return new CaptureRequest.TenderInput(TENDER_TYPE, marketplace + ":" + externalId, total, Map.of("settlement", SETTLEMENT, "marketplace", marketplace));
    }

    /** Amazon {@code ShipmentServiceLevelCategory} → OMS ship method. */
    static String shipMethod(Object level) {
        String s = level == null ? "" : String.valueOf(level).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "expedited", "secondday", "second_day", "scheduleddelivery" -> "air_2day";
            case "nextday", "next_day", "priority", "sameday", "same_day" -> "air_next_day";
            default -> "ground";
        };
    }

    static String addressType(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return s.startsWith("comm") ? "commercial" : s.startsWith("res") ? "residential" : s;
    }

    static Money sum(List<CaptureRequest.LineInput> lines, String ccy) {
        Money m = Money.zero(ccy);
        for (var l : lines) m = m.add(l.unitPrice().multiply(l.qty()).subtract(l.discount() == null ? Money.zero(ccy) : l.discount()));
        return m;
    }

    static Money money(Object amount, String ccy) {
        try { return new Money(new BigDecimal(String.valueOf(amount).trim()), ccy == null ? "USD" : ccy.toUpperCase(Locale.ROOT)); }
        catch (NumberFormatException e) { throw new ValidationException("Invalid amount '" + amount + "'"); }
    }

    static int integer(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return 0;
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { throw new ValidationException("Invalid quantity '" + o + "'"); }
    }

    static Instant instant(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        try { return Instant.parse(s); } catch (DateTimeParseException e) {
            try { return OffsetDateTime.parse(s).toInstant(); } catch (DateTimeParseException e2) { return null; }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    static String str(Object o, String dflt) { return o == null || String.valueOf(o).isBlank() ? dflt : String.valueOf(o).trim(); }

    static void putIf(Map<String, Object> m, String k, Object v) { if (v != null) m.put(k, v); }
}
