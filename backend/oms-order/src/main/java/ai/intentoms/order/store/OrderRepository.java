package ai.intentoms.order.store;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.*;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Normalised persistence for the canonical order. Writes are optimistic on {@code orders.version}; callers append the
 * matching ledger event in the same transaction (see OrderService). Hot reads (get/search) use covering indexes.
 */
@Repository
public class OrderRepository {
    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Order insert(Order o) {
        String t = TenantContext.require();
        Instant now = Instant.now();
        Order saved = new Order(o.id(), o.channel(), o.channelOrderId(), o.orderType(), o.customerRef(), o.customer(), o.lines(), o.addresses(), o.charges(), o.tenders(), o.notes(),
            o.fulfilmentGroups() == null ? List.of() : o.fulfilmentGroups(), o.intent(), o.provenance(), o.totals(), o.margin(), o.stateModelId(), o.stateModelVersion(), o.status(), o.shipMethod(),
            o.currency() == null ? "USD" : o.currency(), o.extensions() == null ? Map.of() : o.extensions(), o.placedAt() == null ? now : o.placedAt(), now, now, 1);
        try {
            jdbc.sql("""
                insert into orders(id, tenant_id, channel, channel_order_id, order_type, customer_ref, customer, intent, provenance, totals, margin, state_model_id, state_model_version,
                  status, ship_method, currency, extensions, placed_at, captured_at, updated_at, version, primary_node_id, grand_total, margin_ratio, below_floor, agent_order, search_text)
                values (:id, :t, :channel, :cref, :type, :cust, :customer, :intent, :prov, :totals, :margin, :smid, :smv, :status, :ship, :cur, :ext, :placed, :captured, :updated, 1,
                  :node, :total, :mratio, :below, :agent, :search)
                """)
                .param("id", saved.id()).param("t", t).param("channel", saved.channel()).param("cref", saved.channelOrderId()).param("type", saved.orderType())
                .param("cust", saved.customerRef()).param("customer", Jsonb.of(saved.customer() == null ? Map.of() : saved.customer()))
                .param("intent", saved.intent() == null ? null : Jsonb.of(saved.intent())).param("prov", saved.provenance() == null ? null : Jsonb.of(saved.provenance()))
                .param("totals", saved.totals() == null ? null : Jsonb.of(saved.totals())).param("margin", saved.margin() == null ? null : Jsonb.of(saved.margin()))
                .param("smid", saved.stateModelId()).param("smv", saved.stateModelVersion()).param("status", saved.status()).param("ship", saved.shipMethod()).param("cur", saved.currency())
                .param("ext", Jsonb.of(saved.extensions())).param("placed", Timestamp.from(saved.placedAt())).param("captured", Timestamp.from(now)).param("updated", Timestamp.from(now))
                .param("node", primaryNode(saved)).param("total", saved.totals() == null || saved.totals().grandTotal() == null ? null : saved.totals().grandTotal().amount())
                .param("mratio", saved.margin() == null ? null : saved.margin().marginRatio()).param("below", saved.margin() != null && saved.margin().belowFloor())
                .param("agent", saved.isAgentOrder()).param("search", searchText(saved))
                .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Order already exists for channel " + o.channel() + " / " + o.channelOrderId(), Map.of("channel", o.channel(), "channelOrderId", o.channelOrderId()));
        }
        writeChildren(t, saved);
        return saved;
    }

    /** Optimistic update: replaces header columns and children; bumps version. */
    @Transactional
    public Order update(Order o) {
        String t = TenantContext.require();
        Instant now = Instant.now();
        int updated = jdbc.sql("""
            update orders set customer = :customer, intent = :intent, provenance = :prov, totals = :totals, margin = :margin, status = :status, ship_method = :ship,
              extensions = :ext, updated_at = :updated, version = version + 1, primary_node_id = :node, grand_total = :total, margin_ratio = :mratio, below_floor = :below,
              state_model_version = :smv, search_text = :search
            where tenant_id = :t and id = :id and version = :v
            """)
            .param("customer", Jsonb.of(o.customer() == null ? Map.of() : o.customer())).param("intent", o.intent() == null ? null : Jsonb.of(o.intent()))
            .param("prov", o.provenance() == null ? null : Jsonb.of(o.provenance())).param("totals", o.totals() == null ? null : Jsonb.of(o.totals()))
            .param("margin", o.margin() == null ? null : Jsonb.of(o.margin())).param("status", o.status()).param("ship", o.shipMethod()).param("ext", Jsonb.of(o.extensions() == null ? Map.of() : o.extensions()))
            .param("updated", Timestamp.from(now)).param("node", primaryNode(o)).param("total", o.totals() == null || o.totals().grandTotal() == null ? null : o.totals().grandTotal().amount())
            .param("mratio", o.margin() == null ? null : o.margin().marginRatio()).param("below", o.margin() != null && o.margin().belowFloor()).param("smv", o.stateModelVersion())
            .param("search", searchText(o)).param("t", t).param("id", o.id()).param("v", o.version())
            .update();
        if (updated == 0) throw new ConflictException("Order " + o.id() + " was modified concurrently (version " + o.version() + ")", Map.of("orderId", o.id()));
        for (String table : List.of("order_line", "order_address", "order_charge", "order_tender", "order_note", "fulfilment_group")) {
            jdbc.sql("delete from " + table + " where tenant_id = :t and order_id = :id").param("t", t).param("id", o.id()).update();
        }
        Order saved = new Order(o.id(), o.channel(), o.channelOrderId(), o.orderType(), o.customerRef(), o.customer(), o.lines(), o.addresses(), o.charges(), o.tenders(), o.notes(),
            o.fulfilmentGroups() == null ? List.of() : o.fulfilmentGroups(), o.intent(), o.provenance(), o.totals(), o.margin(), o.stateModelId(), o.stateModelVersion(), o.status(), o.shipMethod(),
            o.currency(), o.extensions(), o.placedAt(), o.capturedAt(), now, o.version() + 1);
        writeChildren(t, saved);
        return saved;
    }

    private void writeChildren(String t, Order o) {
        for (OrderLine l : o.lines()) {
            jdbc.sql("insert into order_line(tenant_id, order_id, line_id, sku, description, qty, unit_price, discount, tax, currency, fulfilment_group_id, status, attributes) values (:t, :o, :l, :sku, :d, :q, :p, :disc, :tax, :cur, :fg, :st, :attr)")
                .param("t", t).param("o", o.id()).param("l", l.lineId()).param("sku", l.sku()).param("d", l.description()).param("q", l.qty())
                .param("p", l.unitPrice().amount()).param("disc", l.discount() == null ? BigDecimal.ZERO : l.discount().amount()).param("tax", l.tax() == null ? BigDecimal.ZERO : l.tax().amount())
                .param("cur", l.unitPrice().currency()).param("fg", l.fulfilmentGroupId()).param("st", l.status()).param("attr", Jsonb.of(l.attributes() == null ? Map.of() : l.attributes())).update();
        }
        if (o.addresses() != null) for (Address a : o.addresses()) {
            jdbc.sql("insert into order_address(tenant_id, order_id, type, address) values (:t, :o, :ty, :a) on conflict (tenant_id, order_id, type) do update set address = excluded.address")
                .param("t", t).param("o", o.id()).param("ty", a.type()).param("a", Jsonb.of(a)).update();
        }
        if (o.charges() != null) for (Charge c : o.charges()) {
            jdbc.sql("insert into order_charge(tenant_id, order_id, type, code, amount, currency, description) values (:t, :o, :ty, :c, :a, :cur, :d)")
                .param("t", t).param("o", o.id()).param("ty", c.type()).param("c", c.code()).param("a", c.amount().amount()).param("cur", c.amount().currency()).param("d", c.description()).update();
        }
        if (o.tenders() != null) for (Tender td : o.tenders()) {
            jdbc.sql("insert into order_tender(tenant_id, order_id, type, token, amount, currency, status, payment_id, details) values (:t, :o, :ty, :tok, :a, :cur, :s, :p, :d)")
                .param("t", t).param("o", o.id()).param("ty", td.type()).param("tok", td.token()).param("a", td.amount().amount()).param("cur", td.amount().currency())
                .param("s", td.status() == null ? "pending" : td.status()).param("p", td.paymentId()).param("d", Jsonb.of(td.details() == null ? Map.of() : td.details())).update();
        }
        if (o.notes() != null) for (Note n : o.notes()) {
            jdbc.sql("insert into order_note(tenant_id, order_id, source, author, text, at, customer_visible, injection_risk) values (:t, :o, :s, :a, :x, :at, :cv, :ir)")
                .param("t", t).param("o", o.id()).param("s", n.source() == null ? "customer" : n.source()).param("a", n.author()).param("x", n.text())
                .param("at", Timestamp.from(n.at() == null ? Instant.now() : n.at())).param("cv", n.customerVisible()).param("ir", n.injectionRisk()).update();
        }
        if (o.fulfilmentGroups() != null) for (FulfilmentGroup g : o.fulfilmentGroups()) {
            jdbc.sql("""
                insert into fulfilment_group(id, tenant_id, order_id, node_id, policy, allocations, promise_date, on_time_probability, ship_method, estimated_cost, currency, status, sourcing_plan_id, decision_record_id, explain)
                values (:id, :t, :o, :n, :p, :alloc, :pd, :otp, :sm, :cost, :cur, :st, :sp, :dr, :ex)
                """)
                .param("id", g.id()).param("t", t).param("o", o.id()).param("n", g.nodeId()).param("p", g.policy()).param("alloc", Jsonb.of(g.allocations() == null ? List.of() : g.allocations()))
                .param("pd", g.promiseDate()).param("otp", g.onTimeProbability()).param("sm", g.shipMethod())
                .param("cost", g.estimatedCost() == null ? null : g.estimatedCost().amount()).param("cur", g.estimatedCost() == null ? o.currency() : g.estimatedCost().currency())
                .param("st", g.status() == null ? "planned" : g.status()).param("sp", g.sourcingPlanId()).param("dr", g.decisionRecordId()).param("ex", Jsonb.of(g.explain() == null ? Map.of() : g.explain())).update();
        }
    }

    public Optional<Order> find(String orderId) {
        String t = TenantContext.require();
        return jdbc.sql("select * from orders where tenant_id = :t and id = :id").param("t", t).param("id", orderId).query().listOfRows().stream().findFirst().map(h -> hydrate(t, h));
    }

    public Order get(String orderId) { return find(orderId).orElseThrow(() -> new NotFoundException("Order " + orderId + " not found")); }

    public Optional<Order> byChannelRef(String channel, String channelOrderId) {
        String t = TenantContext.require();
        return jdbc.sql("select * from orders where tenant_id = :t and channel = :c and channel_order_id = :r").param("t", t).param("c", channel).param("r", channelOrderId)
            .query().listOfRows().stream().findFirst().map(h -> hydrate(t, h));
    }

    public List<Order> findAll(Collection<String> ids) {
        List<Order> out = new ArrayList<>();
        for (String id : ids) find(id).ifPresent(out::add);
        return out;
    }

    public Page<OrderSummary> search(OrderQuery q) {
        String t = TenantContext.require();
        StringBuilder where = new StringBuilder(" where o.tenant_id = :t");
        Map<String, Object> params = new HashMap<>();
        params.put("t", t);
        if (q.statuses() != null && !q.statuses().isEmpty()) { where.append(" and o.status in (:statuses)"); params.put("statuses", q.statuses()); }
        if (q.channel() != null) { where.append(" and o.channel = :channel"); params.put("channel", q.channel()); }
        if (q.orderType() != null) { where.append(" and o.order_type = :otype"); params.put("otype", q.orderType()); }
        if (q.customerRef() != null) { where.append(" and o.customer_ref = :cref"); params.put("cref", q.customerRef()); }
        if (q.nodeId() != null) { where.append(" and exists (select 1 from fulfilment_group g where g.tenant_id = o.tenant_id and g.order_id = o.id and g.node_id = :node and g.status not in ('rejected', 'cancelled'))"); params.put("node", q.nodeId()); }
        if (q.placedFrom() != null) { where.append(" and o.placed_at >= :from"); params.put("from", Timestamp.from(q.placedFrom())); }
        if (q.placedTo() != null) { where.append(" and o.placed_at < :to"); params.put("to", Timestamp.from(q.placedTo())); }
        if (q.agentOrders() != null) { where.append(" and o.agent_order = :agent"); params.put("agent", q.agentOrders()); }
        if (q.belowFloor() != null) { where.append(" and o.below_floor = :below"); params.put("below", q.belowFloor()); }
        if (q.text() != null && !q.text().isBlank()) { where.append(" and (o.id = :text or o.channel_order_id = :text or o.customer_ref = :text or o.search_text ilike :textlike)"); params.put("text", q.text().trim()); params.put("textlike", "%" + q.text().trim() + "%"); }
        long total = bind(jdbc.sql("select count(*) from orders o" + where), params).query(Long.class).single();
        var rows = bind(jdbc.sql("""
            select o.id, o.channel, o.channel_order_id, o.order_type, o.customer_ref, o.status, o.grand_total, o.currency, o.intent->>'policy' as intent_policy, o.agent_order,
                   o.primary_node_id, o.margin_ratio, o.placed_at, o.updated_at, (select count(*) from order_line l where l.tenant_id = o.tenant_id and l.order_id = o.id) as line_count
            from orders o""" + where + " order by o.placed_at desc limit :limit offset :offset"), params)
            .param("limit", q.limit()).param("offset", q.offset()).query().listOfRows();
        List<OrderSummary> items = rows.stream().map(r -> new OrderSummary((String) r.get("id"), (String) r.get("channel"), (String) r.get("channel_order_id"), (String) r.get("order_type"),
            (String) r.get("customer_ref"), (String) r.get("status"), (BigDecimal) r.get("grand_total"), (String) r.get("currency"), (String) r.get("intent_policy"),
            Boolean.TRUE.equals(r.get("agent_order")), (String) r.get("primary_node_id"), (Double) r.get("margin_ratio"), ts(r.get("placed_at")), ts(r.get("updated_at")),
            ((Number) r.get("line_count")).intValue())).toList();
        return Page.of(items, total, q.offset(), q.limit());
    }

    /** Status counts for the control tower (FR-MON-01). */
    public List<Map<String, Object>> statusCounts(String channel, String orderType) {
        String t = TenantContext.require();
        StringBuilder sql = new StringBuilder("select status, order_type, channel, primary_node_id, count(*) as n, min(placed_at) as oldest from orders where tenant_id = :t and status not in ('delivered','cancelled','picked_up','refunded')");
        var q = jdbc.sql(sql + (channel != null ? " and channel = :c" : "") + (orderType != null ? " and order_type = :ot" : "") + " group by status, order_type, channel, primary_node_id").param("t", t);
        if (channel != null) q = q.param("c", channel);
        if (orderType != null) q = q.param("ot", orderType);
        return q.query().listOfRows();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec;
    }

    private Order hydrate(String t, Map<String, Object> h) {
        String id = (String) h.get("id");
        String currency = (String) h.get("currency");
        List<OrderLine> lines = jdbc.sql("select * from order_line where tenant_id = :t and order_id = :o order by line_id").param("t", t).param("o", id).query().listOfRows().stream()
            .map(r -> new OrderLine((String) r.get("line_id"), (String) r.get("sku"), (String) r.get("description"), ((Number) r.get("qty")).intValue(),
                new Money((BigDecimal) r.get("unit_price"), (String) r.get("currency")), new Money((BigDecimal) r.get("discount"), (String) r.get("currency")),
                new Money((BigDecimal) r.get("tax"), (String) r.get("currency")), (String) r.get("fulfilment_group_id"), (String) r.get("status"), Json.readMap(r.get("attributes").toString()))).toList();
        List<Address> addresses = jdbc.sql("select address::text as a from order_address where tenant_id = :t and order_id = :o").param("t", t).param("o", id).query(String.class).list().stream()
            .map(s -> Json.read(s, Address.class)).toList();
        List<Charge> charges = jdbc.sql("select * from order_charge where tenant_id = :t and order_id = :o order by id").param("t", t).param("o", id).query().listOfRows().stream()
            .map(r -> new Charge((String) r.get("type"), (String) r.get("code"), new Money((BigDecimal) r.get("amount"), (String) r.get("currency")), (String) r.get("description"))).toList();
        List<Tender> tenders = jdbc.sql("select * from order_tender where tenant_id = :t and order_id = :o order by id").param("t", t).param("o", id).query().listOfRows().stream()
            .map(r -> new Tender((String) r.get("type"), (String) r.get("token"), new Money((BigDecimal) r.get("amount"), (String) r.get("currency")), (String) r.get("status"), (String) r.get("payment_id"), Json.readMap(r.get("details").toString()))).toList();
        List<Note> notes = jdbc.sql("select * from order_note where tenant_id = :t and order_id = :o order by id").param("t", t).param("o", id).query().listOfRows().stream()
            .map(r -> new Note((String) r.get("source"), (String) r.get("author"), (String) r.get("text"), ts(r.get("at")), Boolean.TRUE.equals(r.get("customer_visible")), (Double) r.get("injection_risk"))).toList();
        List<FulfilmentGroup> groups = jdbc.sql("select * from fulfilment_group where tenant_id = :t and order_id = :o order by created_at").param("t", t).param("o", id).query().listOfRows().stream()
            .map(r -> new FulfilmentGroup((String) r.get("id"), (String) r.get("node_id"), (String) r.get("policy"),
                Json.read(r.get("allocations").toString(), new TypeReference<List<FulfilmentGroup.Allocation>>() {}),
                r.get("promise_date") == null ? null : ((java.sql.Date) r.get("promise_date")).toLocalDate(), (Double) r.get("on_time_probability"), (String) r.get("ship_method"),
                r.get("estimated_cost") == null ? null : new Money((BigDecimal) r.get("estimated_cost"), (String) r.get("currency")), (String) r.get("status"), (String) r.get("sourcing_plan_id"),
                (String) r.get("decision_record_id"), Json.readMap(r.get("explain").toString()))).toList();
        return new Order(id, (String) h.get("channel"), (String) h.get("channel_order_id"), (String) h.get("order_type"), (String) h.get("customer_ref"),
            h.get("customer") == null ? null : Json.read(h.get("customer").toString(), Customer.class), lines, addresses, charges, tenders, notes, groups,
            h.get("intent") == null ? null : Json.read(h.get("intent").toString(), IntentProfile.class),
            h.get("provenance") == null ? null : Json.read(h.get("provenance").toString(), AgentProvenance.class),
            h.get("totals") == null ? null : Json.read(h.get("totals").toString(), Totals.class),
            h.get("margin") == null ? null : Json.read(h.get("margin").toString(), MarginSnapshot.class),
            (String) h.get("state_model_id"), ((Number) h.get("state_model_version")).intValue(), (String) h.get("status"), (String) h.get("ship_method"), currency,
            Json.readMap(h.get("extensions").toString()), ts(h.get("placed_at")), ts(h.get("captured_at")), ts(h.get("updated_at")), ((Number) h.get("version")).intValue());
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }

    /** First active (not rejected or cancelled) fulfilment group's node; re-sourced orders therefore list their new node. */
    private static String primaryNode(Order o) {
        if (o.fulfilmentGroups() == null || o.fulfilmentGroups().isEmpty()) return null;
        return o.fulfilmentGroups().stream()
            .filter(g -> g.status() == null || !(g.status().equals("rejected") || g.status().equals("cancelled")))
            .map(FulfilmentGroup::nodeId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static String searchText(Order o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.id()).append(' ').append(o.channelOrderId()).append(' ');
        if (o.customerRef() != null) sb.append(o.customerRef()).append(' ');
        if (o.customer() != null && o.customer().email() != null) sb.append(o.customer().email()).append(' ');
        if (o.customer() != null && o.customer().name() != null) sb.append(o.customer().name()).append(' ');
        for (OrderLine l : o.lines()) sb.append(l.sku()).append(' ');
        o.shipTo().ifPresent(a -> sb.append(a.zip()).append(' ').append(a.city()).append(' '));
        return sb.toString();
    }
}
