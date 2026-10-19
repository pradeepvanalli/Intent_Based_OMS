package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * FS-03 + CX-01, built together since both come from the same raw signal. A lane is carrier x service x origin node x
 * destination ZIP3 ({@code <carrier>|<service>|<node>|<zip3>}); services are never pooled, because ground and express
 * from the same node are different lanes. Every delivery is also counted on the carrier x service x origin lane
 * ({@code <carrier>|<service>|<node>}), used only for calibration while the ZIP3 lane is thin, never for transit days
 * (a pooled destination says nothing about how long one destination takes). Per lane: the transit-time distribution
 * (P50, P90), the observed on-time rate, the calibration offset (observed rate minus the probability before lane
 * calibration, {@code LANE_CALIBRATION_OFFSET}) and the residual error after calibration (observed rate minus the
 * probability the orders were actually given, {@code LANE_CALIBRATION_ERROR}, CX-01's target is within 3 points).
 * Read live by sourcing plans, the order's promise date and {@code POST /promise} through {@code ParameterResolver}
 * ({@code promise.lane_transit_days}, {@code promise.lane_calibration}).
 */
@Component
public class LaneDeliveryLearner implements EventSubscriber, GovernedLearner {
    public static final String KEY = "lane_transit";
    public static final String TRANSIT_PARAMETER = "promise.lane_transit_days";
    public static final String CALIBRATION_PARAMETER = "promise.lane_calibration";
    private static final Logger log = LoggerFactory.getLogger(LaneDeliveryLearner.class);
    private static final int SAMPLE_CAP = 200;
    static final int MIN_TRANSIT_SAMPLES = 5;
    static final int MIN_RATE_SAMPLES = 10;

    private final NodeAssignmentRepository assignments;
    private final LaneDeliverySampleRepository samples;
    private final FulfilmentFacts facts;
    private final LearnerGovernance governance;

    public LaneDeliveryLearner(NodeAssignmentRepository assignments, LaneDeliverySampleRepository samples, FulfilmentFacts facts, LearnerGovernance governance) {
        this.assignments = assignments;
        this.samples = samples;
        this.facts = facts;
        this.governance = governance;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("order.shipped", "event.shipped", "order.delivered", "event.delivered"); }

    @Override
    public void on(Event e) {
        var previousTenant = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            if (!governance.begin(KEY).mayRun()) return;
            governance.recordRunThrottled(KEY);
            switch (e.type()) {
                case "order.shipped", "event.shipped" -> onShipped(e);
                case "order.delivered", "event.delivered" -> onDelivered(e);
                default -> {}
            }
        } catch (RuntimeException ex) {
            log.warn("Lane delivery learning failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            previousTenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private void onShipped(Event e) {
        Object carrier = e.payload() == null ? null : e.payload().get("carrier");
        if (carrier == null) return;
        Object node = e.payload().get("nodeId");
        assignments.recordShipped(e.aggregateId(), node == null ? null : String.valueOf(node), String.valueOf(carrier), e.occurredAt());
    }

    private void onDelivered(Event e) {
        for (NodeAssignmentRepository.Assignment a : NodeAssignmentRepository.deliveredBy(assignments.assignmentsFor(e.aggregateId()), e.payload())) {
            if (a.carrier() == null || a.shippedAt() == null) continue; // no lane without both ends known
            double transitDays = Math.max(0, Duration.between(a.shippedAt(), e.occurredAt()).toMinutes() / 1440.0);
            boolean onTime = a.promisedEta() == null || !e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(a.promisedEta());
            Double base = a.baseOnTime() != null ? a.baseOnTime() : a.statedOnTime();
            for (String lane : lanesFor(a.carrier(), a.shipMethod(), a.nodeId(), a.destZip3())) {
                if (samples.record(lane, transitDays, onTime, a.statedOnTime(), base, e.aggregateId(), a.nodeId())) publishFacts(lane);
            }
        }
    }

    void publishFacts(String laneKey) {
        List<Double> recent = samples.recentTransitDays(laneKey, SAMPLE_CAP);
        if (recent.size() < MIN_TRANSIT_SAMPLES) return; // too few samples for a meaningful percentile yet
        List<Double> sorted = recent.stream().sorted(Comparator.naturalOrder()).toList();
        facts.number(SubjectType.LANE, laneKey, FactKey.LANE_TRANSIT_DAYS_P50, round(percentile(sorted, 0.50)), recent.size(), KEY, 0.01);
        facts.number(SubjectType.LANE, laneKey, FactKey.LANE_TRANSIT_DAYS_P90, round(percentile(sorted, 0.90)), recent.size(), KEY, 0.01);
        var stats = samples.onTimeStats(laneKey, SAMPLE_CAP);
        if (stats.total() >= MIN_RATE_SAMPLES) {
            facts.number(SubjectType.LANE, laneKey, FactKey.LANE_ON_TIME_RATE, round((double) stats.onTimeCount() / stats.total()), (int) stats.total(), KEY, 0.001);
        }
        if (stats.baseSamples() >= MIN_RATE_SAMPLES) {
            // The offset is learned against the probability before calibration, so applying it does not feed back into it.
            double observed = (double) stats.onTimeOfBase() / stats.baseSamples();
            facts.number(SubjectType.LANE, laneKey, FactKey.LANE_CALIBRATION_OFFSET, round(observed - stats.meanBase()), (int) stats.baseSamples(), KEY, 0.001);
        }
        if (stats.statedSamples() >= MIN_RATE_SAMPLES) {
            // The residual: how far what the orders were told is from what happened, after any calibration.
            double observed = (double) stats.onTimeOfStated() / stats.statedSamples();
            facts.number(SubjectType.LANE, laneKey, FactKey.LANE_CALIBRATION_ERROR, round(observed - stats.meanStated()), (int) stats.statedSamples(), KEY, 0.001);
        }
    }

    /** The ZIP3 lane (when the destination is known) and the carrier x service x origin lane every delivery also counts on. */
    public static List<String> lanesFor(String carrier, String service, String nodeId, String zip3) {
        String coarse = laneKey(carrier, service, nodeId, null);
        return zip3 == null || zip3.isBlank() ? List.of(coarse) : List.of(laneKey(carrier, service, nodeId, zip3), coarse);
    }

    /** {@code <carrier>|<service>|<node>[|<zip3>]}; an unknown service is "any". */
    public static String laneKey(String carrier, String service, String nodeId, String zip3) {
        String base = carrier + "|" + service(service) + "|" + nodeId;
        return zip3 == null || zip3.isBlank() ? base : base + "|" + zip3;
    }

    public static String service(String service) {
        return service == null || service.isBlank() ? "any" : service.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static String zip3(String zip) {
        if (zip == null) return null;
        String digits = zip.replaceAll("[^0-9]", "");
        return digits.length() >= 3 ? digits.substring(0, 3) : null;
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000d; }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Carrier lane transit times", "Learns how long each carrier lane really takes to each area and whether stated on-time odds hold up.",
            LearnerDescriptor.Loop.FAST, "FS-03, CX-01").readsOutcomesOf("sourcing_node_score").feeds(TRANSIT_PARAMETER, CALIBRATION_PARAMETER);
    }
}
