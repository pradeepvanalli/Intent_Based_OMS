package ai.intentoms.inventory.learning;

import ai.intentoms.inventory.config.InventoryConfigKeys;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.inventory.service.ReservationService;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.promotion.PromotionService;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SOO IU-01..IU-05 through the real Spring context and PostgreSQL. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class InventoryLearningIntegrationTest extends PostgresTestBase {
    @Autowired InventoryService inventory;
    @Autowired ReservationService reservations;
    @Autowired StockAgeLearner stockAge;
    @Autowired SafetyStockLearner safetyStock;
    @Autowired ReservationTtlLearner ttlLearner;
    @Autowired InboundReliabilityLearner reliability;
    @Autowired TransferRecommender transfers;
    @Autowired InventoryLearningRepository repo;
    @Autowired InventoryOutcomeMapper mapper;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache factCache;
    @Autowired ParameterResolver resolver;
    @Autowired LearnerGovernance governance;
    @Autowired ProposalRepository proposals;
    @Autowired PromotionService promotion;
    @Autowired TenantConfigStore config;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        for (String family : List.of(LearnedInventoryParameters.SAFETY_STOCK, LearnedInventoryParameters.INBOUND_RELIABILITY, LearnedInventoryParameters.RESERVATION_TTL))
            resolver.revoke(family, "test");
        config.delete(InventoryConfigKeys.INCLUDE_INBOUND);
        config.delete("learning.safety_stock.max");
        factCache.invalidateAll();
    }

    private static String sku() { return "sku-" + UUID.randomUUID().toString().substring(0, 8); }
    private static String node() { return "node-" + UUID.randomUUID().toString().substring(0, 6); }

    /** An order-backed reservation created {@code daysAgo} days ago (backdated directly; the service always stamps now). */
    private void demand(String sku, String node, int qty, int daysAgo) {
        jdbc.sql("""
            insert into inventory_reservation(id, tenant_id, sku, node_id, qty, source, ref_id, order_id, status, created_at, updated_at)
            values (:id, :t, :s, :n, :q, 'order', :o, :o, 'CONSUMED', :at, :at)
            """).param("id", Ids.prefixed("rsv")).param("t", TenantContext.require()).param("s", sku).param("n", node).param("q", qty)
            .param("o", "ord-" + Ids.shortId()).param("at", Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo)).minusSeconds(60))).update();
    }

    private Instant receivedAt(String sku, String node) {
        Object ts = jdbc.sql("select received_at from inventory_position where tenant_id = :t and sku = :s and node_id = :n and supply_type = 'on_hand' and demand_type = ''")
            .param("t", TenantContext.require()).param("s", sku).param("n", node).query().singleRow().get("received_at");
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    @Test
    void receivedAtTracksTheOldestUnit() {
        String sku = sku(), n = node();
        Instant t0 = Instant.now().minus(Duration.ofDays(10));
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 5, 0, t0)), "wms");
        assertThat(receivedAt(sku, n)).isCloseTo(t0, within(Duration.ofSeconds(1)));
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 8, 0, t0.plusSeconds(3600))), "wms");
        assertThat(receivedAt(sku, n)).as("topping up keeps the oldest unit's date").isCloseTo(t0, within(Duration.ofSeconds(1)));
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 0, 0, t0.plusSeconds(7200))), "wms");
        assertThat(receivedAt(sku, n)).as("sold out clears it").isNull();
        inventory.adjust(sku, n, 3, "receipt", Actor.system("test"));
        assertThat(receivedAt(sku, n)).isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));
        Instant explicit = Instant.now().minus(Duration.ofDays(40));
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 9, 0, Instant.now(), explicit, null)), "wms");
        assertThat(receivedAt(sku, n)).as("a feed may state receivedAt").isCloseTo(explicit, within(Duration.ofSeconds(1)));
    }

    @Test
    void agedStockGetsAFactAndStrandedStockOneSignalNamingTheOrderSourcedElsewhere() {
        String sku = sku(), aged = node(), other = node();
        inventory.upsert(List.of(new InventoryPosition(sku, aged, "on_hand", null, 12, 0, Instant.now(), Instant.now().minus(Duration.ofDays(120)), null),
            new InventoryPosition(sku, other, "on_hand", null, 20, 0, null)), "wms");
        Reservation r = reservations.reserve(sku, other, 2, Reservation.SOURCE_ORDER, "ord-elsewhere-" + sku, null);

        StockAgeLearner.Pass first = stockAge.sweepTenant();
        assertThat(first.strandedSignals()).isEqualTo(1);
        LearnedFact age = facts.active(SubjectType.SKU_NODE, sku + "|" + aged, FactKey.STOCK_AGE_DAYS).orElseThrow();
        assertThat(((TypedValue.NumberValue) age.value()).value()).isBetween(119.0, 121.0);
        assertThat(facts.active(SubjectType.SKU_NODE, sku + "|" + other, FactKey.STOCK_AGE_DAYS)).as("fresh stock has no age fact").isEmpty();

        Map<String, Object> signal = jdbc.sql("select order_id, days_aged from inventory_stranded_signal where tenant_id = :t and sku = :s")
            .param("t", TenantContext.require()).param("s", sku).query().singleRow();
        assertThat(signal.get("order_id")).isEqualTo("ord-elsewhere-" + sku);
        assertThat(stockAge.sweepTenant().strandedSignals()).as("signalled once per aged position").isZero();

        Event stranded = new Event(Ids.id(), TenantContext.require(), "node", aged, 1, StockAgeLearner.STRANDED_EVENT,
            Map.of("sku", sku, "nodeId", aged, "daysAged", 120, "orderId", r.refId()), Actor.system("t"), null, r.refId(), Map.of(), Instant.now());
        assertThat(mapper.map(stranded)).isEqualTo(new OutcomeData.InventoryOutcome(true, false, 120));

        // Selling the aged stock reports the age on reservation.consumed, which maps to aged-stock relief.
        Reservation sale = reservations.reserve(sku, aged, 1, Reservation.SOURCE_ORDER, "ord-aged-" + sku, null);
        reservations.consume(sale.id());
        Map<String, Object> consumed = jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :id and type = 'reservation.consumed'")
            .param("t", TenantContext.require()).param("id", sale.id()).query().singleRow();
        assertThat((String) consumed.get("p")).contains("stockAgeDays");
    }

    @Test
    void learnedSafetyStockReachesAtpOnlyWhilePromotedAndTheLearnerIsAboveL0() {
        String sku = sku(), n = node();
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 100, 0, null), new InventoryPosition(sku, n, "on_hand", "safety", 10, 0, null)), "wms");
        assertThat(inventory.atp(sku, n).available()).isEqualTo(90);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.SKU_NODE, sku + "|" + n, FactKey.SAFETY_STOCK_UNITS, new TypedValue.NumberValue(25), 1.0, 0,
            "promotion-service", 1, Instant.now(), null, Instant.now()));
        assertThat(inventory.atp(sku, n).available()).as("not promoted for live use").isEqualTo(90);

        resolver.promote(LearnedInventoryParameters.SAFETY_STOCK, SafetyStockLearner.KEY, 0.0, 200.0, null, null, "test");
        assertThat(inventory.atp(sku, n).available()).isEqualTo(75);
        assertThat(inventory.atp(sku, n).safety()).isEqualTo(25);

        governance.setAutonomy(SafetyStockLearner.KEY, AutonomyLevel.L0_OBSERVE, "test");
        try {
            assertThat(inventory.atp(sku, n).available()).as("L0 never feeds live paths").isEqualTo(90);
        } finally {
            governance.setAutonomy(SafetyStockLearner.KEY, AutonomyLevel.L1_RECOMMEND, "test");
        }
    }

    @Test
    void safetyStockLearnerDraftsOneBoundedProposalThatPromotionApplies() {
        String sku = sku(), n = node();
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 500, 0, null)), "wms");
        int[] daily = {2, 30, 5, 40, 1, 25, 3, 35, 4, 28};
        for (int d = 0; d < daily.length; d++) demand(sku, n, daily[d], d + 1);
        config.set("learning.safety_stock.max", 20);

        var rec = safetyStock.recommend(Instant.now()).stream().filter(x -> x.sku().equals(sku)).findFirst().orElseThrow();
        assertThat(rec.recommended()).as("clamped to the tenant max").isEqualTo(20);
        assertThat(rec.demandDays()).isEqualTo(10);

        List<String> drafted = safetyStock.proposeForTenant();
        String target = "parameter:SKU_NODE:" + sku + "|" + n + ":SAFETY_STOCK_UNITS";
        Proposal p = drafted.stream().map(proposals::get).filter(x -> x.changeset().target().toString().equals(target)).findFirst().orElseThrow();
        assertThat(p.changeset().changes()).singleElement().isInstanceOfSatisfying(PolicyChange.ParameterChange.class, c -> {
            assertThat(((Number) c.to()).intValue()).isEqualTo(20);
            assertThat(((Number) c.from()).intValue()).isZero();
        });
        assertThat(safetyStock.proposeForTenant()).as("no second proposal while one is open")
            .noneMatch(id -> proposals.get(id).changeset().target().toString().equals(target));

        // Replay evaluation of parameter targets is the experimentation workstream's; stand in for its positive report.
        proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.02, "confidenceInterval", Map.of("lower", 0.005, "upper", 0.04)), Instant.now());
        proposals.approve(p.proposalId(), "user:planner", Instant.now());
        promotion.promote(p.proposalId(), Actor.parse("user:planner"));
        LearnedFact applied = facts.active(SubjectType.SKU_NODE, sku + "|" + n, FactKey.SAFETY_STOCK_UNITS).orElseThrow();
        assertThat(((TypedValue.NumberValue) applied.value()).value()).isEqualTo(20.0);
    }

    @Test
    void reservationTtlIsLearnedFromConversionCurvesAndAppliedToCartsOnlyWhenLive() {
        Instant base = Instant.now().minus(Duration.ofDays(2));
        for (int i = 1; i <= 25; i++) {
            Instant created = base.plusSeconds(i * 600L);
            jdbc.sql("""
                insert into inventory_reservation(id, tenant_id, sku, node_id, qty, source, ref_id, order_id, status, created_at, updated_at, converted_at)
                values (:id, :t, 'ttl-sku', 'ttl-node', 1, 'order', :r, :r, 'CONSUMED', :c, :c, :conv)
                """).param("id", Ids.prefixed("rsv")).param("t", TenantContext.require()).param("r", "cart-" + i).param("c", Timestamp.from(created))
                .param("conv", Timestamp.from(created.plus(Duration.ofMinutes(i)))).update();
        }
        // Other test classes in this database also convert carts, so the expected value is computed from every conversion.
        double expected = ReservationTtlLearner.ttlFor(repo.conversionMinutes(Instant.now().minus(Duration.ofDays(30))));
        assertThat(ttlLearner.learnForTenant()).contains(expected);
        LearnedFact ttlFact = facts.active(SubjectType.SEGMENT, LearnedInventoryParameters.TENANT_SEGMENT, FactKey.RESERVATION_TTL_MINUTES).orElseThrow();
        assertThat(((TypedValue.NumberValue) ttlFact.value()).value()).isEqualTo(expected);

        String sku = sku(), n = node();
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 10, 0, null)), "wms");
        assertThat(reservations.reserve(sku, n, 1, "cart", "cart-a-" + sku, null).expiresAt()).as("no TTL until promoted").isNull();
        resolver.promote(LearnedInventoryParameters.RESERVATION_TTL, ReservationTtlLearner.KEY, 5.0, 1440.0, null, null, "test");
        Instant expires = reservations.reserve(sku, n, 1, "cart", "cart-b-" + sku, null).expiresAt();
        assertThat(expires).isCloseTo(Instant.now().plus(Duration.ofSeconds(Math.round(expected * 60))), within(Duration.ofMinutes(1)));
        assertThat(reservations.reserve(sku, n, 1, Reservation.SOURCE_ORDER, "ord-ttl-" + sku, null).expiresAt()).as("orders never expire").isNull();
    }

    @Test
    void supplierReliabilityWeightsInboundInAtpOncePromoted() {
        String sku = sku(), n = node(), supplier = "sup-" + Ids.shortId();
        config.set(InventoryConfigKeys.INCLUDE_INBOUND, true);
        inventory.upsert(List.of(new InventoryPosition(sku, n, "on_hand", null, 10, 0, null), new InventoryPosition(sku, n, "po", null, 100, 0, null, null, supplier)), "erp");
        Instant now = Instant.now();
        for (int i = 0; i < 10; i++) {
            repo.insertReceipt(new InventoryLearningRepository.Receipt(supplier, "asn-" + i, "x", n, 100, 50, now.minus(Duration.ofDays(i + 1)), now.minus(Duration.ofDays(i + 1))));
        }
        assertThat(repo.insertReceipt(new InventoryLearningRepository.Receipt(supplier, "asn-0", "x", n, 100, 50, now, now))).as("ASN re-send is idempotent").isEmpty();
        double learned = reliability.learnForTenant().get(supplier);
        assertThat(learned).isCloseTo((10 * 0.5 + 0.9 * 5) / 15, within(1e-9));
        assertThat(inventory.atp(sku, n).available()).isEqualTo(110);
        resolver.promote(LearnedInventoryParameters.INBOUND_RELIABILITY, InboundReliabilityLearner.KEY, 0.0, 1.0, null, null, "test");
        assertThat(inventory.atp(sku, n).available()).isEqualTo(10 + (int) Math.floor(100 * learned));
    }

    @Test
    void transferSuggestionsMoveSurplusToNodesAboutToRunOut() {
        String sku = sku(), full = node(), empty = node();
        inventory.upsert(List.of(new InventoryPosition(sku, full, "on_hand", null, 100, 0, null), new InventoryPosition(sku, empty, "on_hand", null, 1, 0, null)), "wms");
        for (int d = 1; d <= 20; d++) demand(sku, empty, 2, d);   // 40 units in 28 days: 10 a week, 0.1 weeks of cover
        List<TransferRecommender.Transfer> recs = transfers.recommendForTenant();
        assertThat(recs).filteredOn(t -> t.sku().equals(sku)).containsExactly(new TransferRecommender.Transfer(sku, full, empty, 19));
        LearnedFact f = facts.active(SubjectType.SKU, sku, FactKey.TRANSFER_RECOMMENDATION).orElseThrow();
        assertThat(((TypedValue.ListValue) f.value()).values()).containsExactly(full + ">" + empty + ":19");
    }
}
