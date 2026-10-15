package ai.intentoms.sourcing;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.model.Money;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.Customer;
import ai.intentoms.order.model.IntentProfile;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.Totals;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.node.NodeLoader;
import ai.intentoms.sourcing.rates.RateCardLoader;
import ai.intentoms.testing.PostgresTestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integration base: a fresh tenant per test seeded with the demo nodes, rate card, decision catalogue and tenant config,
 * the clock frozen at a Tuesday noon UTC (before every node cutoff) so ETAs are deterministic.
 */
@SpringBootTest(classes = SourcingTestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class SourcingTestBase extends PostgresTestBase {
    public static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();
    public static final Instant NOW = Instant.parse("2026-10-06T16:00:00Z");
    public static final Address CHICAGO = new Address("ship_to", "Jane", "1 Michigan Ave", null, "Chicago", "IL", "60611", "US", null, "residential", true, null, null);
    public static final Address NEW_YORK = new Address("ship_to", "Jane", "5th Ave", null, "New York", "NY", "10001", "US", null, "residential", true, null, null);
    public static final Address DALLAS = new Address("ship_to", "Jane", "Main St", null, "Dallas", "TX", "75201", "US", null, "residential", true, null, null);

    @Autowired protected NodeLoader nodeLoader;
    @Autowired protected RateCardLoader rateCardLoader;
    @Autowired protected DecisionDefinitionLoader decisionLoader;
    @Autowired protected InventoryService inventory;
    @Autowired protected OrderRepository orders;
    @Autowired protected TenantConfigStore config;
    @Autowired protected TimeSource time;
    protected String tenant;

    @BeforeEach
    void seedTenant() {
        tenant = "src_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
        time.override(Clock.fixed(NOW, ZoneOffset.UTC));
        nodeLoader.loadFile(DEMO.resolve("nodes.yaml"));
        rateCardLoader.loadFile(DEMO.resolve("shipping-rates.yaml"));
        decisionLoader.loadDirectory(DEMO.resolve("decisions"));
        config.set("sourcing.margin_floor", 0.12);
        config.set("sourcing.max_split_packages", 3);
        config.set("sourcing.split_penalty_usd", 12.0);
        config.set("sourcing.channel_default_policy", Map.of("web", "ship_as_available", "marketplace", "ship_complete", "pos", "ship_complete", "agent", "ship_complete", "b2b", "ship_complete"));
        config.set("finance.payment_fee_rate", 0.029);
        config.set("finance.returns_reserve_rate", 0.05);
    }

    @AfterEach
    void resetClock() { time.reset(); }

    protected void stock(String sku, String nodeId, int qty) { inventory.upsert(List.of(InventoryPosition.onHand(sku, nodeId, qty)), "test"); }

    protected OrderLine line(String lineId, String sku, int qty, double price, double cogs) { return line(lineId, sku, qty, price, cogs, Map.of()); }

    protected OrderLine line(String lineId, String sku, int qty, double price, double cogs, Map<String, Object> extra) {
        Map<String, Object> attrs = new java.util.LinkedHashMap<>(Map.of("cogs", cogs, "weight_kg", 0.5));
        attrs.putAll(extra);
        return new OrderLine(lineId, sku, sku, qty, Money.usd(price), Money.zero("USD"), Money.zero("USD"), null, "awaiting_sourcing", attrs);
    }

    protected Order order(String channel, IntentProfile intent, Address shipTo, OrderLine... lines) {
        Money merch = Money.zero("USD");
        for (OrderLine l : lines) merch = merch.add(l.extendedPrice());
        Totals totals = new Totals(merch, Money.zero("USD"), Money.zero("USD"), Money.zero("USD"), Money.zero("USD"), Money.zero("USD"), merch);
        Order o = new Order(Ids.prefixed("ord"), channel, Ids.shortId(), "standard_ship", "cust-1", new Customer("cust-1", "Jane", "jane@example.com", null, "gold", true, Map.of("split_acceptance_rate", 0.6)),
            List.of(lines), List.of(shipTo), List.of(), List.of(), List.of(), List.of(), intent, null, totals, null, "order.standard_ship", 1, "awaiting_sourcing", "ground", "USD",
            Map.of(), NOW, NOW, NOW, 1);
        return orders.insert(o);
    }

    protected static IntentProfile intent(String policy, Boolean splitOk) { return new IntentProfile(policy, 0.9, "explicit", List.of(policy), null, splitOk, false); }

    protected List<Map<String, Object>> events(String aggregateType, String aggregateId, String type) {
        var rows = jdbc.sql("select type, payload::text as payload from event where tenant_id = :t and aggregate_type = :at and aggregate_id = :aid and type = :type order by sequence")
            .param("t", tenant).param("at", aggregateType).param("aid", aggregateId).param("type", type).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(Json.readMap((String) r.get("payload")));
        return out;
    }

    protected static BigDecimal bd(String v) { return new BigDecimal(v); }
}
