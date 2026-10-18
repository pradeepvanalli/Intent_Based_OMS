package ai.intentoms.inventory.learning;

import ai.intentoms.inventory.config.InventoryConfigKeys;
import ai.intentoms.inventory.store.InventoryPositionStore;
import ai.intentoms.inventory.store.InventoryPositionStore.AtpRow;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where learned inventory parameters meet the live ATP and reservation paths. Every value goes through
 * {@link ParameterResolver}: until its family is promoted for live use (and while its learner is on, not paused and
 * above L0) the static value is served, so a tenant behaves exactly as before any learning.
 * <ul>
 *   <li>{@link #SAFETY_STOCK} (IU-01): the promoted {@code SAFETY_STOCK_UNITS} fact for {@code SKU_NODE "<sku>|<node>"}
 *       replaces the fed {@code safety} demand, within the promoted range and step.</li>
 *   <li>{@link #INBOUND_RELIABILITY} (IU-05): inbound supply counts toward ATP at its supplier's learned reliability
 *       ({@code INBOUND_RELIABILITY}, subject {@code SUPPLIER}); a row without a supplier counts in full.</li>
 *   <li>{@link #RESERVATION_TTL} (IU-02): the TTL given to a cart reservation whose caller passed none; static value from
 *       {@code inventory.reservation_ttl_minutes}, none when unset (the reservation then never expires, as before).</li>
 * </ul>
 */
@Component
public class LearnedInventoryParameters {
    public static final String SAFETY_STOCK = "inventory.safety_stock";
    public static final String INBOUND_RELIABILITY = "inventory.inbound_reliability";
    public static final String RESERVATION_TTL = "inventory.reservation_ttl";
    /** Tenant-wide facts live on this segment id. */
    public static final String TENANT_SEGMENT = "default";

    private final ParameterResolver resolver;
    private final LearnedFactCache facts;
    private final InventoryPositionStore positions;
    private final TenantConfigStore config;

    public LearnedInventoryParameters(ParameterResolver resolver, LearnedFactCache facts, InventoryPositionStore positions, TenantConfigStore config) {
        this.resolver = resolver;
        this.facts = facts;
        this.positions = positions;
        this.config = config;
    }

    public static String skuNode(String sku, String nodeId) { return sku + "|" + nodeId; }

    /**
     * ATP rows with learned safety stock and reliability-weighted inbound applied. Returns {@code rows} unchanged (no
     * extra reads) when neither family is live, which is the case for every tenant until a promotion.
     */
    public List<AtpRow> apply(String tenant, List<AtpRow> rows, boolean includeInbound) {
        if (rows.isEmpty()) return rows;
        boolean safetyLive = resolver.isLive(SAFETY_STOCK);
        boolean inboundLive = includeInbound && resolver.isLive(INBOUND_RELIABILITY);
        if (!safetyLive && !inboundLive) return rows;
        Map<String, Integer> weightedInbound = inboundLive ? weightedInbound(tenant, rows) : Map.of();
        List<AtpRow> out = new ArrayList<>(rows.size());
        for (AtpRow r : rows) {
            int safety = safetyLive ? safetyStock(r.sku(), r.nodeId(), r.safety()) : r.safety();
            int inbound = inboundLive ? weightedInbound.getOrDefault(skuNode(r.sku(), r.nodeId()), r.inbound()) : r.inbound();
            out.add(new AtpRow(r.sku(), r.nodeId(), r.onHand(), inbound, r.externalReserved(), r.allocated(), safety, r.confidence(), r.reserved()));
        }
        return out;
    }

    /** IU-01: the safety stock to hold for (sku, node): the promoted learned value, or the fed static safety. */
    public int safetyStock(String sku, String nodeId, int staticSafety) {
        Double learned = number(facts.active(SubjectType.SKU_NODE, skuNode(sku, nodeId), FactKey.SAFETY_STOCK_UNITS));
        if (learned == null) return staticSafety;
        Double v = resolver.explain(SAFETY_STOCK, (double) staticSafety, learned).value();
        return v == null ? staticSafety : (int) Math.max(0, Math.round(v));
    }

    /** IU-05: share of a supplier's inbound quantity to count on (1.0 = all of it). */
    public double inboundReliability(String supplierId) {
        if (supplierId == null) return 1.0;
        Double learned = number(facts.active(SubjectType.SUPPLIER, supplierId, FactKey.INBOUND_RELIABILITY));
        Double v = resolver.explain(INBOUND_RELIABILITY, 1.0, learned).value();
        return v == null ? 1.0 : Math.max(0.0, Math.min(1.0, v));
    }

    /** IU-02: TTL for a cart reservation whose caller gave none; empty means no expiry (the behaviour before learning). */
    public Optional<Duration> defaultReservationTtl() {
        Object configured = config.get(InventoryConfigKeys.RESERVATION_TTL_MINUTES).orElse(null);
        Double staticMinutes = configured instanceof Number n ? n.doubleValue() : configured == null ? null : Double.valueOf(configured.toString());
        Double learned = number(facts.active(SubjectType.SEGMENT, TENANT_SEGMENT, FactKey.RESERVATION_TTL_MINUTES));
        Double minutes = resolver.explain(RESERVATION_TTL, staticMinutes, learned).value();
        return minutes == null || minutes <= 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(Math.round(minutes * 60)));
    }

    private Map<String, Integer> weightedInbound(String tenant, List<AtpRow> rows) {
        Set<String> skus = new LinkedHashSet<>();
        rows.forEach(r -> skus.add(r.sku()));
        Map<String, Double> sums = new HashMap<>();
        Map<String, Double> reliabilityBySupplier = new HashMap<>();
        for (InventoryPositionStore.InboundRow in : positions.inboundRows(tenant, skus)) {
            double rel = in.supplierId() == null ? 1.0 : reliabilityBySupplier.computeIfAbsent(in.supplierId(), this::inboundReliability);
            sums.merge(skuNode(in.sku(), in.nodeId()), in.qty() * rel, Double::sum);
        }
        Map<String, Integer> out = new HashMap<>();
        sums.forEach((k, v) -> out.put(k, (int) Math.floor(v + 1e-9)));
        for (AtpRow r : rows) out.putIfAbsent(skuNode(r.sku(), r.nodeId()), 0);
        return out;
    }

    private static Double number(Optional<LearnedFact> f) {
        return f.filter(x -> x.value() instanceof TypedValue.NumberValue).map(x -> ((TypedValue.NumberValue) x.value()).value()).orElse(null);
    }
}
