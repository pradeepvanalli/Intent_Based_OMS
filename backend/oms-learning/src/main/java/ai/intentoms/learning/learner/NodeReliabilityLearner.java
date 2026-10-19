package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * FS-02: a Beta-binomial posterior per node x SKU class x day part, fed from ledger events (fast loop, no LLM). SKU
 * class is the catalogue category the order line carries ({@code attributes.category}, written onto each package of
 * {@code order.sourced}); day part is when the node was asked to fulfil (morning, afternoon, evening, night). Facts are
 * published at three levels so a decision can use the most specific one with enough evidence: the node overall
 * ({@code NODE:<node>}), the node for one SKU class ({@code NODE:<node>|<class>}) and the node for one SKU class in one
 * day part ({@code NODE:<node>|<class>|<daypart>}).
 *
 * <p>FS-06: also publishes {@code NODE_REJECT_PENALTY}, the penalty applied when this node rejects an order: the
 * tenant's static {@code sourcing.reject_penalty} scaled by how this node's reject rate compares with the fleet's
 * (a node that rejects twice as often as average gets twice the penalty, within [0.05, 0.9]). The penalty keeps
 * decaying with the 7-day half-life sourcing already applies. Live use goes through {@code ParameterResolver}
 * ({@code sourcing.reject_penalty}).
 *
 * <p>On-time is the node's own job, an on-time pick (FS-02): the package left the node by the date sourcing set for it
 * ({@code packages[].shipBy} on {@code order.sourced}, compared with the first {@code order.shipped} from that node).
 * Whether the carrier then delivered on time is the lane's business ({@code LaneDeliveryLearner}). Older events with no
 * ship-by date fall back to delivery against the promised ETA. Day part is the node's local time when sourcing assigned
 * it ({@code packages[].dayPart}), else the tenant's time zone ({@code tenant.timezone}), else UTC.
 *
 * <p>Evidence fades with a half-life ({@code learning.node_reliability.half_life_days}, default 30): a node that rejected
 * a lot last quarter but has shipped cleanly since recovers, and its reject penalty with it (FS-06).
 */
@Component
public class NodeReliabilityLearner implements EventSubscriber, GovernedLearner {
    public static final String KEY = "node_reliability";
    public static final String REJECT_PENALTY_PARAMETER = "sourcing.reject_penalty";
    private static final Logger log = LoggerFactory.getLogger(NodeReliabilityLearner.class);
    static final String SKU_CLASS = "default";
    static final int MIN_SPECIFIC_SAMPLES = 10;
    public static final String HALF_LIFE_KEY = "learning.node_reliability.half_life_days";

    private final NodeAssignmentRepository assignments;
    private final NodeReliabilityStatRepository stats;
    private final FulfilmentFacts facts;
    private final TenantConfigStore config;
    private final LearnerGovernance governance;

    public NodeReliabilityLearner(NodeAssignmentRepository assignments, NodeReliabilityStatRepository stats, FulfilmentFacts facts, TenantConfigStore config,
                                  LearnerGovernance governance) {
        this.assignments = assignments;
        this.stats = stats;
        this.facts = facts;
        this.config = config;
        this.governance = governance;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("order.sourced", "order.resourced", "order.shipped", "event.shipped", "order.delivered", "event.delivered"); }

    @Override
    public void on(Event e) {
        var previousTenant = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            if (!governance.begin(KEY).mayRun()) return;
            governance.recordRunThrottled(KEY);
            switch (e.type()) {
                case "order.sourced" -> onSourced(e);
                case "order.resourced" -> onResourced(e);
                case "order.shipped", "event.shipped" -> onShipped(e);
                case "order.delivered", "event.delivered" -> onDelivered(e);
                default -> {}
            }
        } catch (RuntimeException ex) {
            log.warn("Node reliability learning failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            previousTenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private void onSourced(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        if (!(p.get("packages") instanceof List<?> list)) return;
        String zip3 = str(p.get("destinationZip3"));
        String method = str(p.get("shipMethod"));
        String fallbackDayPart = dayPart(e.occurredAt(), tenantZone());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> pkg) || pkg.get("nodeId") == null) continue;
            String skuClass = pkg.get("skuClass") == null ? SKU_CLASS : String.valueOf(pkg.get("skuClass"));
            Integer backlog = pkg.get("backlogUnits") instanceof Number n ? n.intValue() : null;
            Double stated = pkg.get("onTime") instanceof Number n ? n.doubleValue() : null;
            Double base = pkg.get("onTimeBase") instanceof Number n ? n.doubleValue() : stated;
            String dayPart = pkg.get("dayPart") == null ? fallbackDayPart : String.valueOf(pkg.get("dayPart"));
            assignments.record(e.aggregateId(), String.valueOf(pkg.get("nodeId")),
                new NodeAssignmentRepository.Sourced(skuClass, parseDate(pkg.get("eta")), zip3, method, dayPart, backlog, stated, parseDate(pkg.get("shipBy")), base),
                e.occurredAt());
        }
    }

    /** FS-02: the first shipment from a node says whether its pick left by the ship-by date sourcing set. */
    private void onShipped(Event e) {
        Object node = e.payload() == null ? null : e.payload().get("nodeId");
        if (node == null) return;
        String nodeId = String.valueOf(node);
        NodeAssignmentRepository.Assignment a = assignments.assignmentsFor(e.aggregateId()).stream().filter(x -> x.nodeId().equals(nodeId)).findFirst().orElse(null);
        if (a == null || a.shipBy() == null) return; // no ship-by date: delivery decides, as before
        boolean onTime = !e.occurredAt().atZone(tenantZone()).toLocalDate().isAfter(a.shipBy());
        if (!assignments.recordPicked(e.aggregateId(), nodeId, onTime)) return; // already counted
        String skuClass = a.skuClass() == null ? SKU_CLASS : a.skuClass();
        String dayPart = a.dayPart() == null ? dayPart(e.occurredAt(), tenantZone()) : a.dayPart();
        stats.recordOnTime(nodeId, skuClass, dayPart, onTime, halfLifeDays());
        publishFacts(nodeId, skuClass, dayPart);
    }

    private void onResourced(Event e) {
        Object rejected = e.payload() == null ? null : e.payload().get("rejectedNodes");
        if (!(rejected instanceof List<?> list)) return;
        List<NodeAssignmentRepository.Assignment> assigned = assignments.assignmentsFor(e.aggregateId());
        for (Object n : list) {
            String nodeId = String.valueOf(n);
            NodeAssignmentRepository.Assignment a = assigned.stream().filter(x -> x.nodeId().equals(nodeId)).findFirst().orElse(null);
            String skuClass = a == null || a.skuClass() == null ? SKU_CLASS : a.skuClass();
            String dayPart = a == null || a.dayPart() == null ? dayPart(e.occurredAt(), tenantZone()) : a.dayPart();
            stats.recordReject(nodeId, skuClass, dayPart, true, halfLifeDays());
            publishFacts(nodeId, skuClass, dayPart);
        }
    }

    private void onDelivered(Event e) {
        LocalDate deliveredOn = e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        for (NodeAssignmentRepository.Assignment a : assignments.assignmentsFor(e.aggregateId())) {
            boolean onTime = a.promisedEta() == null || !deliveredOn.isAfter(a.promisedEta());
            if (!assignments.recordDelivered(e.aggregateId(), a.nodeId(), e.occurredAt(), onTime)) continue; // already counted
            String skuClass = a.skuClass() == null ? SKU_CLASS : a.skuClass();
            String dayPart = a.dayPart() == null ? dayPart(e.occurredAt(), tenantZone()) : a.dayPart();
            // A node with a ship-by date was already judged on its pick; delivery only says it did not reject.
            if (a.pickedOnTime() == null && a.shipBy() == null) stats.recordOnTime(a.nodeId(), skuClass, dayPart, onTime, halfLifeDays());
            stats.recordReject(a.nodeId(), skuClass, dayPart, false, halfLifeDays());
            publishFacts(a.nodeId(), skuClass, dayPart);
        }
    }

    void publishFacts(String nodeId, String skuClass, String dayPart) {
        var node = stats.pooled(nodeId, null);
        facts.number(SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE, round(node.onTimeMean()), node.sampleSize(), KEY, 0.0005);
        facts.number(SubjectType.NODE, nodeId, FactKey.NODE_REJECT_RATE, round(node.rejectMean()), node.sampleSize(), KEY, 0.0005);
        var cls = stats.pooled(nodeId, skuClass);
        if (cls.sampleSize() >= MIN_SPECIFIC_SAMPLES) {
            facts.number(SubjectType.NODE, subject(nodeId, skuClass, null), FactKey.NODE_ON_TIME_RATE, round(cls.onTimeMean()), cls.sampleSize(), KEY, 0.0005);
            facts.number(SubjectType.NODE, subject(nodeId, skuClass, null), FactKey.NODE_REJECT_RATE, round(cls.rejectMean()), cls.sampleSize(), KEY, 0.0005);
        }
        var specific = stats.posterior(nodeId, skuClass, dayPart);
        if (specific.sampleSize() >= MIN_SPECIFIC_SAMPLES) {
            facts.number(SubjectType.NODE, subject(nodeId, skuClass, dayPart), FactKey.NODE_ON_TIME_RATE, round(specific.onTimeMean()), specific.sampleSize(), KEY, 0.0005);
            facts.number(SubjectType.NODE, subject(nodeId, skuClass, dayPart), FactKey.NODE_REJECT_RATE, round(specific.rejectMean()), specific.sampleSize(), KEY, 0.0005);
        }
        double base = config.getDouble(REJECT_PENALTY_PARAMETER, 0.3);
        double fleet = stats.fleetRejectMean();
        if (node.sampleSize() >= MIN_SPECIFIC_SAMPLES && fleet > 0) {
            facts.number(SubjectType.NODE, nodeId, FactKey.NODE_REJECT_PENALTY, round(rejectPenalty(base, node.rejectMean(), fleet)), node.sampleSize(), KEY, 0.005);
        }
    }

    /** FS-06: the static penalty scaled by this node's reject rate relative to the fleet's, kept within [0.05, 0.9]. */
    static double rejectPenalty(double base, double nodeRejectRate, double fleetRejectRate) {
        if (fleetRejectRate <= 0) return base;
        return Math.max(0.05, Math.min(0.9, base * nodeRejectRate / fleetRejectRate));
    }

    /** Fact subject id for a node, optionally narrowed to a SKU class and day part. */
    public static String subject(String nodeId, String skuClass, String dayPart) {
        if (skuClass == null) return nodeId;
        return dayPart == null ? nodeId + "|" + skuClass : nodeId + "|" + skuClass + "|" + dayPart;
    }

    public static String dayPart(Instant at) { return dayPart(at, ZoneOffset.UTC); }

    /** Night, morning, afternoon or evening in the given zone (a node's or the tenant's local time). */
    public static String dayPart(Instant at, java.time.ZoneId zone) {
        int hour = at.atZone(zone).getHour();
        if (hour < 6) return "night";
        if (hour < 12) return "morning";
        if (hour < 18) return "afternoon";
        return "evening";
    }

    /** The tenant's time zone ({@code tenant.timezone}), UTC when unset or invalid. */
    java.time.ZoneId tenantZone() {
        try { return java.time.ZoneId.of(config.getString("tenant.timezone", "UTC")); }
        catch (RuntimeException e) { return ZoneOffset.UTC; }
    }

    double halfLifeDays() {
        try { return Math.max(1.0, config.getDouble(HALF_LIFE_KEY, 30.0)); } catch (RuntimeException e) { return 30.0; }
    }

    static LocalDate parseDate(Object v) {
        if (v == null) return null;
        try { return LocalDate.parse(String.valueOf(v).length() > 10 ? String.valueOf(v).substring(0, 10) : String.valueOf(v)); }
        catch (RuntimeException e) { return null; }
    }

    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o); }
    private static double round(double v) { return Math.round(v * 10_000) / 10_000d; }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Node reliability", "Learns how often each store or warehouse ships on time and rejects orders, by product category and time of day.",
                LearnerDescriptor.Loop.FAST, "FS-02, FS-06").readsOutcomesOf("sourcing_node_score")
            .feeds(NodeReliabilityFeatures.REJECT_RATE, NodeReliabilityFeatures.ON_TIME_RATE, REJECT_PENALTY_PARAMETER);
    }
}
