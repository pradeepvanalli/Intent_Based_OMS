package ai.intentoms.inventory.learning;

import ai.intentoms.inventory.config.InventoryConfigKeys;
import ai.intentoms.inventory.service.InventoryEvents;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * IU-03 signals. Daily, per tenant:
 * <ul>
 *   <li>a {@code STOCK_AGE_DAYS} fact (subject {@code SKU_NODE "<sku>|<node>"}) for every position whose oldest unit is at
 *       least {@code inventory.aged_days} old (default 30), closed again once the stock sells through. The sourcing
 *       tie-break reads these to prefer shipping aged stock when costs are close;</li>
 *   <li>one {@code inventory.stranded} event per aged position that is at least {@code inventory.stranded_days} old
 *       (default 90) and has not sold in a third of that time. Its payload names the most recent order that took the same
 *       sku from another node, if any, so that sourcing decision's episode records the stranded-stock outcome.</li>
 * </ul>
 * The stranded signal is an operational fact and is emitted even while this learner is paused; the facts are learning
 * output and follow the governance gate.
 */
@Component
public class StockAgeLearner implements GovernedLearner {
    public static final String KEY = "inventory_stock_age";
    public static final String STRANDED_EVENT = "inventory.stranded";
    private static final Logger log = LoggerFactory.getLogger(StockAgeLearner.class);
    private static final Actor ACTOR = Actor.system(KEY);

    private final InventoryLearningRepository repo;
    private final InventoryLearnerSupport support;
    private final LearnedFactRepository facts;
    private final LearnerGovernance governance;
    private final TenantConfigStore config;
    private final EventStore events;
    private final Metrics metrics;

    public StockAgeLearner(InventoryLearningRepository repo, InventoryLearnerSupport support, LearnedFactRepository facts, LearnerGovernance governance,
                           TenantConfigStore config, EventStore events, Metrics metrics) {
        this.repo = repo;
        this.support = support;
        this.facts = facts;
        this.governance = governance;
        this.config = config;
        this.events = events;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${oms.inventory.learning.stock-age-sweep-ms:86400000}", initialDelayString = "${oms.inventory.learning.stock-age-initial-ms:900000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Stock age sweep failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        for (String tenant : support.tenants()) {
            try { TenantContext.runAs(tenant, this::sweepTenant); }
            catch (RuntimeException e) { log.warn("Stock age sweep failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    /** Result of one tenant pass, for tests and the run endpoint's logs. */
    public record Pass(int agedFacts, int closedFacts, int strandedSignals) {}

    public Pass sweepTenant() {
        Instant now = Instant.now();
        int agedDays = config.getInt(InventoryConfigKeys.AGED_DAYS, 30);
        int strandedDays = config.getInt(InventoryConfigKeys.STRANDED_DAYS, 90);
        boolean learn = governance.begin(KEY).mayRun();
        if (learn) governance.recordRun(KEY);
        Map<String, LearnedFact> existing = new HashMap<>();
        if (learn) for (LearnedFact f : facts.activeByKey(SubjectType.SKU_NODE, FactKey.STOCK_AGE_DAYS, 100_000)) existing.put(f.subjectId(), f);
        Set<String> stillAged = new HashSet<>();
        int written = 0, closed = 0, stranded = 0;
        for (InventoryLearningRepository.Stock s : repo.stock()) {
            if (s.onHand() <= 0 || s.receivedAt() == null) continue;
            int age = (int) Math.max(0, Duration.between(s.receivedAt(), now).toDays());
            String id = LearnedInventoryParameters.skuNode(s.sku(), s.nodeId());
            if (learn && age >= agedDays) {
                stillAged.add(id);
                if (support.writeNumber(SubjectType.SKU_NODE, id, FactKey.STOCK_AGE_DAYS, age, 1.0, s.onHand(), KEY, 1.0)) written++;
            }
            if (age >= strandedDays && !repo.soldSince(s.sku(), s.nodeId(), now.minus(Duration.ofDays(Math.max(1, strandedDays / 3))))) {
                String orderId = repo.recentOrderElsewhere(s.sku(), s.nodeId(), now.minus(Duration.ofDays(14))).orElse(null);
                if (repo.recordStranded(s.sku(), s.nodeId(), s.receivedAt(), s.onHand(), age, orderId)) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("sku", s.sku());
                    p.put("nodeId", s.nodeId());
                    p.put("qty", s.onHand());
                    p.put("daysAged", age);
                    p.put("receivedAt", s.receivedAt().toString());
                    if (orderId != null) p.put("orderId", orderId);
                    events.append(InventoryEvents.NODE, s.nodeId(), new NewEvent(STRANDED_EVENT, p, ACTOR, null, orderId, Map.of()));
                    stranded++;
                }
            }
        }
        if (learn) {
            for (String id : existing.keySet()) {
                if (stillAged.contains(id)) continue;
                support.close(SubjectType.SKU_NODE, id, FactKey.STOCK_AGE_DAYS);
                closed++;
            }
        }
        metrics.count("oms.inventory.learning.stranded_signals", stranded, "tenant", TenantContext.require());
        return new Pass(written, closed, stranded);
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Stock age", "Tracks how long stock has sat at each location and flags stock that has stopped selling, so orders can be sent to use it up first.",
            LearnerDescriptor.Loop.FAST, "IU-03").features("inventory_position.received_at", "inventory_reservation");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
