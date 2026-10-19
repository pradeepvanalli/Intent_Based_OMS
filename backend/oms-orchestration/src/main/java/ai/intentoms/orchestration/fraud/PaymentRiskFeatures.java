package ai.intentoms.orchestration.fraud;

import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.Tender;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Typed fraud signals for the {@code payment_risk} (FD-01) and {@code rto_risk} (FD-02) decisions, computed from the
 * tenant's own order history at decision time. Only counts and flags go into the input, never card numbers or
 * addresses: the address is reduced to an {@link AddressTokens} token, tenders to their tokens.
 *
 * <p>Velocity is card-testing velocity, not repeat purchasing: the number of <i>additional</i> distinct tender tokens
 * the same customer used in the window (a loyal customer reordering with the same card scores 0). Linkage counts are
 * other customers sharing this order's address, device or card in 30 days. Store-pickup orders share the store's
 * address by design and are left out of address linkage.
 */
@Component
public class PaymentRiskFeatures {
    static final Duration LINKAGE_WINDOW = Duration.ofDays(30);
    static final Duration HISTORY_WINDOW = Duration.ofDays(365);

    private final JdbcClient jdbc;
    private final TimeSource time;

    public PaymentRiskFeatures(JdbcClient jdbc, TimeSource time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    public Map<String, Object> paymentRisk(Order o) {
        Instant now = time.now();
        Set<String> tokens = tokens(o);
        String customer = o.customerRef();
        boolean pickup = o.address("pickup").isPresent();
        String shipToken = pickup ? null : o.shipTo().map(AddressTokens::token).orElse(null);
        String billToken = o.address("bill_to").map(AddressTokens::token).orElse(null);
        String device = device(o);

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("velocity_1h", extraTokens(customer, o.id(), tokens, now.minus(Duration.ofHours(1))));
        payment.put("velocity_24h", extraTokens(customer, o.id(), tokens, now.minus(Duration.ofHours(24))));
        payment.put("address_linkage_count", shipToken == null ? 0 : addressLinkage(shipToken, customer, o.id(), now.minus(LINKAGE_WINDOW)));
        payment.put("device_linkage_count", device == null ? 0 : deviceLinkage(device, customer, o.id(), now.minus(LINKAGE_WINDOW)));
        payment.put("token_customer_count", tokens.isEmpty() ? 0 : tokenLinkage(tokens, customer, o.id(), now.minus(LINKAGE_WINDOW)));
        payment.put("billing_shipping_mismatch", billToken != null && shipToken != null && !billToken.equals(shipToken));
        payment.put("tender_types", o.tenders().stream().map(Tender::type).filter(t -> t != null).distinct().toList());

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("mandate_present", o.provenance() != null && o.provenance().mandateId() != null);
        if (o.isAgentOrder()) agent.put("id", o.provenance().agentId());

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("order", orderPart(o));
        in.put("payment", payment);
        in.put("agent", agent);
        Map<String, Object> address = new LinkedHashMap<>();
        if (shipToken != null) address.put("token", shipToken);
        address.put("verified", o.shipTo().map(Address::verified).orElse(false));
        in.put("address", address);
        if (customer != null) in.put("customer", Map.of("ref", customer));
        return in;
    }

    public Map<String, Object> rtoRisk(Order o) {
        Instant since = time.now().minus(HISTORY_WINDOW);
        String customer = o.customerRef();
        String shipToken = o.shipTo().map(AddressTokens::token).orElse(null);
        Map<String, Object> order = orderPart(o);
        order.put("payment_method", "cod");

        Map<String, Object> address = new LinkedHashMap<>();
        if (shipToken != null) address.put("token", shipToken);
        address.put("verification_status", o.shipTo().map(a -> a.verified() ? "verified" : "unverified").orElse("missing"));
        address.put("prior_failed_deliveries", shipToken == null ? 0 : carrierEvents(List.of("order.returned_to_sender", "order.carrier_exception"), "addressToken", shipToken, o.id(), since));
        address.put("prior_refused_count", shipToken == null ? 0 : carrierEvents(List.of("order.returned_to_sender"), "addressToken", shipToken, o.id(), since));
        address.put("prior_delivered_count", shipToken == null ? 0 : deliveredTo(shipToken, o.id(), since));

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("refused_12m", customer == null ? 0 : carrierEvents(List.of("order.returned_to_sender"), "customerRef", customer, o.id(), since));
        history.put("orders_12m", customer == null ? 0 : ordersOf(customer, o.id(), since));
        Map<String, Object> cust = new LinkedHashMap<>();
        if (customer != null) cust.put("ref", customer);
        cust.put("history", history);

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("order", order);
        in.put("address", address);
        in.put("customer", cust);
        return in;
    }

    public static boolean hasCod(Order o) {
        return o.tenders().stream().anyMatch(t -> "cod".equalsIgnoreCase(t.type()));
    }

    // ---- signals

    private int extraTokens(String customer, String orderId, Set<String> current, Instant since) {
        if (customer == null) return 0;
        Set<String> all = new LinkedHashSet<>(current);
        all.addAll(jdbc.sql("""
            select distinct t.token from orders o join order_tender t on t.tenant_id = o.tenant_id and t.order_id = o.id
            where o.tenant_id = :t and o.customer_ref = :c and o.id <> :oid and o.placed_at >= :since and t.token is not null
            """).param("t", TenantContext.require()).param("c", customer).param("oid", orderId).param("since", Timestamp.from(since)).query(String.class).list());
        return Math.max(0, all.size() - 1);
    }

    private int addressLinkage(String token, String customer, String orderId, Instant since) {
        return count("""
            select count(distinct o.customer_ref) from orders o join order_address a on a.tenant_id = o.tenant_id and a.order_id = o.id and a.type = 'ship_to'
            where o.tenant_id = :t and o.id <> :oid and o.placed_at >= :since and o.customer_ref is not null and o.customer_ref <> :c
              and %s = :tok
              and not exists (select 1 from order_address p where p.tenant_id = o.tenant_id and p.order_id = o.id and p.type = 'pickup')
            """.formatted(AddressTokens.SQL), Map.of("tok", token), customer, orderId, since);
    }

    private int deviceLinkage(String device, String customer, String orderId, Instant since) {
        return count("""
            select count(distinct o.customer_ref) from orders o
            where o.tenant_id = :t and o.id <> :oid and o.placed_at >= :since and o.customer_ref is not null and o.customer_ref <> :c
              and (o.extensions->>'deviceId' = :dev or exists (select 1 from order_tender t where t.tenant_id = o.tenant_id and t.order_id = o.id and t.details->>'deviceId' = :dev))
            """, Map.of("dev", device), customer, orderId, since);
    }

    private int tokenLinkage(Set<String> tokens, String customer, String orderId, Instant since) {
        return count("""
            select count(distinct o.customer_ref) from orders o join order_tender t on t.tenant_id = o.tenant_id and t.order_id = o.id
            where o.tenant_id = :t and o.id <> :oid and o.placed_at >= :since and o.customer_ref is not null and o.customer_ref <> :c
              and t.token in (:tokens) and t.type <> 'cod'
            """, Map.of("tokens", new ArrayList<>(tokens)), customer, orderId, since);
    }

    private int carrierEvents(List<String> types, String field, String value, String orderId, Instant since) {
        return jdbc.sql("""
            select count(distinct aggregate_id) from event
            where tenant_id = :t and type in (:types) and occurred_at >= :since and aggregate_id <> :oid and payload->>'%s' = :v
            """.formatted(field))
            .param("t", TenantContext.require()).param("types", types).param("since", Timestamp.from(since)).param("oid", orderId).param("v", value)
            .query(Integer.class).single();
    }

    /** Orders already delivered to this address (any customer): a successful delivery is the strongest sign the
     * address is real and reachable, so a cash-on-delivery order going there does not need an address confirmation. */
    private int deliveredTo(String token, String orderId, Instant since) {
        return jdbc.sql("""
            select count(distinct o.id) from orders o join order_address a on a.tenant_id = o.tenant_id and a.order_id = o.id and a.type = 'ship_to'
            where o.tenant_id = :t and o.id <> :oid and o.placed_at >= :since and o.status in ('delivered', 'completed') and %s = :tok
            """.formatted(AddressTokens.SQL))
            .param("t", TenantContext.require()).param("oid", orderId).param("since", Timestamp.from(since)).param("tok", token)
            .query(Integer.class).single();
    }

    private int ordersOf(String customer, String orderId, Instant since) {
        return jdbc.sql("select count(*) from orders where tenant_id = :t and customer_ref = :c and id <> :oid and placed_at >= :since")
            .param("t", TenantContext.require()).param("c", customer).param("oid", orderId).param("since", Timestamp.from(since)).query(Integer.class).single();
    }

    private int count(String sql, Map<String, Object> extra, String customer, String orderId, Instant since) {
        var spec = jdbc.sql(sql).param("t", TenantContext.require()).param("oid", orderId).param("since", Timestamp.from(since)).param("c", customer == null ? "" : customer);
        for (var e : extra.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec.query(Integer.class).single();
    }

    // ---- order facts

    private static Map<String, Object> orderPart(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("total", o.totals() == null || o.totals().grandTotal() == null ? 0.0 : o.totals().grandTotal().amount().doubleValue());
        m.put("channel", o.channel());
        m.put("type", o.orderType());
        return m;
    }

    private static Set<String> tokens(Order o) {
        Set<String> s = new LinkedHashSet<>();
        for (Tender t : o.tenders()) if (t.token() != null && !"cod".equalsIgnoreCase(t.type())) s.add(t.token());
        return s;
    }

    /** Device fingerprint id from the tender details or the order extensions, when the storefront sends one. */
    static String device(Order o) {
        for (Tender t : o.tenders()) {
            Object d = t.details() == null ? null : t.details().get("deviceId");
            if (d != null && !String.valueOf(d).isBlank()) return String.valueOf(d);
        }
        Object d = Optional.ofNullable(o.extensions()).map(e -> e.get("deviceId")).orElse(null);
        return d == null || String.valueOf(d).isBlank() ? null : String.valueOf(d);
    }
}
