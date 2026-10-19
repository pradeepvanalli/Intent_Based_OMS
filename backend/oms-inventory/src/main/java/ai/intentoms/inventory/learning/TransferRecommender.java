package ai.intentoms.inventory.learning;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IU-04: weekly stock-transfer suggestions. Recommend only: nothing here moves stock. Each suggestion is a typed
 * {@link TransferRecommendation} {sku, from, to, qty, splitsAvoided, cost} stored for planners
 * ({@code GET /api/v1/inventory/learning/transfers}), and the SKU's {@code TRANSFER_RECOMMENDATION} fact lists them
 * ({@code "<fromNode>><toNode>:<units>"}). From the last 28 days of order demand: a node with more than
 * {@code learning.transfer.excess_weeks} (default 8) weeks of cover can give away what it holds above 4 weeks of cover
 * (all of it when it has no demand); a node with demand and under 1 week of cover asks for enough to reach 2 weeks.
 * Largest surplus is matched to largest need first.
 *
 * <p>{@code splitsAvoided}: orders in the same 28 days that split because the receiving node lacked this SKU (the SKU
 * came from another node while the order's other lines were at the receiving node), shared across the moves into
 * that node by units and never more than the units moved. {@code cost}: {@code inventory.transfer.fixed_cost_usd}
 * (default 15) plus {@code inventory.transfer.unit_cost_usd} (default 0.50) per unit; {@code splitSavings} prices the
 * avoided splits at {@code sourcing.split_penalty_usd} (default 12). A SKU whose network is balanced has its previous
 * suggestions closed.
 */
@Component
public class TransferRecommender implements GovernedLearner {
    public static final String KEY = "inventory_transfer";
    private static final Logger log = LoggerFactory.getLogger(TransferRecommender.class);

    private final InventoryLearningRepository repo;
    private final InventoryLearnerSupport support;
    private final LearnedFactRepository facts;
    private final LearnerGovernance governance;
    private final TenantConfigStore config;

    public TransferRecommender(InventoryLearningRepository repo, InventoryLearnerSupport support, LearnedFactRepository facts, LearnerGovernance governance, TenantConfigStore config) {
        this.repo = repo;
        this.support = support;
        this.facts = facts;
        this.governance = governance;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${oms.inventory.learning.transfer-sweep-ms:604800000}", initialDelayString = "${oms.inventory.learning.transfer-initial-ms:2400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Transfer recommender failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        for (String tenant : support.tenants()) {
            try { TenantContext.runAs(tenant, this::recommendForTenant); }
            catch (RuntimeException e) { log.warn("Transfer recommender failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    public record Transfer(String sku, String fromNode, String toNode, int units) {
        public String label() { return fromNode + ">" + toNode + ":" + units; }
    }

    /** IU-04's typed recommendation: what to move, how many split shipments it should save, and what the move costs. */
    public record TransferRecommendation(String sku, String fromNode, String toNode, int qty, int splitsAvoided, BigDecimal cost, BigDecimal splitSavings,
                                         String currency, Instant generatedAt) {
        public String label() { return fromNode + ">" + toNode + ":" + qty; }
        public boolean worthIt() { return splitSavings.compareTo(cost) > 0; }
    }

    /** Writes this tenant's suggestions; returns them. */
    public List<TransferRecommendation> recommendForTenant() {
        if (!governance.begin(KEY).mayRun()) return List.of();
        governance.recordRun(KEY);
        double excessWeeks = config.getDouble("learning.transfer.excess_weeks", 8);
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofDays(28));
        Map<String, Double> weekly = new HashMap<>();
        for (InventoryLearningRepository.DailyDemand d : repo.dailyDemand(since)) weekly.merge(LearnedInventoryParameters.skuNode(d.sku(), d.nodeId()), d.units() / 4.0, Double::sum);
        Map<String, Map<String, Integer>> onHandBySku = new LinkedHashMap<>();
        for (InventoryLearningRepository.Stock s : repo.stock()) onHandBySku.computeIfAbsent(s.sku(), k -> new LinkedHashMap<>()).put(s.nodeId(), Math.max(0, s.onHand()));
        List<TransferRecommendation> all = new ArrayList<>();
        Set<String> withSuggestion = new HashSet<>();
        for (var e : onHandBySku.entrySet()) {
            List<Transfer> forSku = plan(e.getKey(), e.getValue(), weekly, excessWeeks);
            if (forSku.isEmpty()) continue;
            withSuggestion.add(e.getKey());
            List<TransferRecommendation> typed = price(forSku, since, now);
            all.addAll(typed);
            repo.replaceTransfers(e.getKey(), typed, now);
            int units = forSku.stream().mapToInt(Transfer::units).sum();
            support.writeList(SubjectType.SKU, e.getKey(), FactKey.TRANSFER_RECOMMENDATION, forSku.stream().map(Transfer::label).toList(), 0.7, units, KEY);
        }
        for (LearnedFact f : facts.activeByKey(SubjectType.SKU, FactKey.TRANSFER_RECOMMENDATION, 100_000)) {
            if (!withSuggestion.contains(f.subjectId())) {
                support.close(SubjectType.SKU, f.subjectId(), FactKey.TRANSFER_RECOMMENDATION);
                repo.closeTransfers(f.subjectId(), now);
            }
        }
        return all;
    }

    /** Adds splits avoided and cost to one SKU's moves. */
    List<TransferRecommendation> price(List<Transfer> moves, Instant since, Instant now) {
        BigDecimal fixed = BigDecimal.valueOf(config.getDouble("inventory.transfer.fixed_cost_usd", 15.0));
        BigDecimal perUnit = BigDecimal.valueOf(config.getDouble("inventory.transfer.unit_cost_usd", 0.50));
        BigDecimal splitCost = BigDecimal.valueOf(config.getDouble("sourcing.split_penalty_usd", 12.0));
        Map<String, Integer> unitsInto = new HashMap<>();
        for (Transfer t : moves) unitsInto.merge(t.toNode(), t.units(), Integer::sum);
        Map<String, Integer> splitsInto = new HashMap<>();
        List<TransferRecommendation> out = new ArrayList<>();
        for (Transfer t : moves) {
            int splits = splitsInto.computeIfAbsent(t.toNode(), n -> repo.splitOrders(t.sku(), n, since));
            int avoided = splitsAvoided(splits, t.units(), unitsInto.get(t.toNode()));
            BigDecimal cost = fixed.add(perUnit.multiply(BigDecimal.valueOf(t.units()))).setScale(2, RoundingMode.HALF_UP);
            BigDecimal savings = splitCost.multiply(BigDecimal.valueOf(avoided)).setScale(2, RoundingMode.HALF_UP);
            out.add(new TransferRecommendation(t.sku(), t.fromNode(), t.toNode(), t.units(), avoided, cost, savings, "USD", now));
        }
        return out;
    }

    /** This move's share (by units) of the splits seen into its node, never more than the units it moves. */
    static int splitsAvoided(int splitsIntoNode, int units, int unitsIntoNode) {
        if (splitsIntoNode <= 0 || units <= 0 || unitsIntoNode <= 0) return 0;
        return Math.min(units, (int) Math.round(splitsIntoNode * (double) units / unitsIntoNode));
    }

    /** Pure matching of surplus to need for one SKU (unit-tested). */
    static List<Transfer> plan(String sku, Map<String, Integer> onHandByNode, Map<String, Double> weeklyBySkuNode, double excessWeeks) {
        Map<String, Integer> surplus = new LinkedHashMap<>();
        Map<String, Integer> need = new LinkedHashMap<>();
        boolean anyDemand = false;
        for (var n : onHandByNode.entrySet()) {
            double w = weeklyBySkuNode.getOrDefault(LearnedInventoryParameters.skuNode(sku, n.getKey()), 0.0);
            int onHand = n.getValue();
            if (w > 0) anyDemand = true;
            if (w <= 0) { if (onHand > 0) surplus.put(n.getKey(), onHand); continue; }
            double cover = onHand / w;
            if (cover > excessWeeks && onHand - 4 * w >= 1) surplus.put(n.getKey(), (int) Math.floor(onHand - 4 * w));
            else if (cover < 1) need.put(n.getKey(), (int) Math.ceil(2 * w - onHand));
        }
        if (!anyDemand || need.isEmpty() || surplus.isEmpty()) return List.of();
        List<Map.Entry<String, Integer>> givers = new ArrayList<>(surplus.entrySet());
        givers.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        List<Map.Entry<String, Integer>> takers = new ArrayList<>(need.entrySet());
        takers.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        List<Transfer> out = new ArrayList<>();
        int gi = 0;
        int[] left = givers.stream().mapToInt(Map.Entry::getValue).toArray();
        for (var t : takers) {
            int want = t.getValue();
            while (want > 0 && gi < givers.size()) {
                int give = Math.min(want, left[gi]);
                if (give > 0) { out.add(new Transfer(sku, givers.get(gi).getKey(), t.getKey(), give)); want -= give; left[gi] -= give; }
                if (left[gi] <= 0) gi++;
            }
        }
        return out;
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Stock transfer suggestions", "Suggests moving stock from locations that have too much to ones about to run out. It only recommends; a planner decides.",
            LearnerDescriptor.Loop.FAST, "IU-04");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
