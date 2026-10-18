package ai.intentoms.learning.learner;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * FS-03 + CX-01, built together since both come from the same raw signal: a transit-time distribution per lane
 * (carrier + origin node — see V1601 for why destination-postal granularity isn't available), and the calibration
 * gap between the on-time probability {@code DefaultPromiseService} states and what actually happens, within 3
 * points (CX-01's target).
 *
 * <p><b>Integration point for DefaultPromiseService</b> (not wired live, per the parent's explicit fallback
 * instruction — its own Caffeine-cached, &lt;300ms-p95 path is too latency-sensitive to risk a blind edit here):
 * read {@code LANE_TRANSIT_DAYS_P50}/{@code P90} learned facts (subjectType=LANE, subjectId="&lt;carrier&gt;|&lt;originNodeId&gt;")
 * as an override for its static transit table once a lane has 20+ samples, the same confidence-gating pattern
 * {@code SoftScorer.overlayLearnedReliability} already uses for FS-02. Transit days here are measured ship-to-deliver
 * (not sourced-to-deliver), using the shipped_at this learner itself now captures on {@code order.shipped}.
 */
@Component
public class LaneDeliveryLearner implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(LaneDeliveryLearner.class);
    private static final String LEARNER_KEY = "lane_transit";
    private static final int SAMPLE_CAP = 200;

    private final NodeAssignmentRepository assignments;
    private final LaneDeliverySampleRepository samples;
    private final LearnedFactRepository facts;

    public LaneDeliveryLearner(NodeAssignmentRepository assignments, LaneDeliverySampleRepository samples, LearnedFactRepository facts) {
        this.assignments = assignments;
        this.samples = samples;
        this.facts = facts;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("order.shipped", "event.shipped", "order.delivered", "event.delivered"); }

    @Override
    public void on(Event e) {
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            switch (e.type()) {
                case "order.shipped", "event.shipped" -> onShipped(e);
                case "order.delivered", "event.delivered" -> onDelivered(e);
                default -> {}
            }
        } catch (RuntimeException ex) {
            log.warn("Lane delivery learning failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    private void onShipped(Event e) {
        Object carrier = e.payload() == null ? null : e.payload().get("carrier");
        if (carrier == null) return;
        assignments.recordShipped(e.aggregateId(), String.valueOf(carrier), e.occurredAt());
    }

    private void onDelivered(Event e) {
        for (NodeAssignmentRepository.Assignment a : assignments.assignmentsFor(e.aggregateId())) {
            if (a.carrier() == null || a.shippedAt() == null) continue; // no lane without both ends known
            double transitDays = Duration.between(a.shippedAt(), e.occurredAt()).toHours() / 24.0;
            boolean onTime = a.promisedEta() == null || !e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(a.promisedEta());
            String laneKey = a.carrier() + "|" + a.nodeId();
            samples.record(laneKey, transitDays, onTime);
            publishFacts(laneKey);
        }
    }

    private void publishFacts(String laneKey) {
        List<Double> recent = samples.recentTransitDays(laneKey, SAMPLE_CAP);
        if (recent.size() < 5) return; // too few samples for a meaningful percentile yet
        List<Double> sorted = recent.stream().sorted(Comparator.naturalOrder()).toList();
        double p50 = percentile(sorted, 0.50), p90 = percentile(sorted, 0.90);
        Instant now = Instant.now();
        double confidence = Math.min(1.0, recent.size() / 30.0);
        facts.supersede(SubjectType.LANE, laneKey, FactKey.LANE_TRANSIT_DAYS_P50, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.LANE, laneKey, FactKey.LANE_TRANSIT_DAYS_P50, new TypedValue.NumberValue(p50), confidence, recent.size(), LEARNER_KEY, 1, now, null, now));
        facts.supersede(SubjectType.LANE, laneKey, FactKey.LANE_TRANSIT_DAYS_P90, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.LANE, laneKey, FactKey.LANE_TRANSIT_DAYS_P90, new TypedValue.NumberValue(p90), confidence, recent.size(), LEARNER_KEY, 1, now, null, now));

        // CX-01: calibration gap between promised on-time probability and observed on-time rate, as a learned fact
        // keyed the same way — DefaultPromiseService's on-time-probability output is the "stated" side of this; the
        // observed side is just the fraction of the same samples that were on_time.
        var stats = samples.onTimeStats(laneKey, SAMPLE_CAP);
        if (stats.total() >= 10) {
            double observedOnTimeRate = (double) stats.onTimeCount() / stats.total();
            facts.supersede(SubjectType.LANE, laneKey, FactKey.LANE_ON_TIME_RATE, now);
            facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.LANE, laneKey, FactKey.LANE_ON_TIME_RATE,
                new TypedValue.NumberValue(observedOnTimeRate), Math.min(1.0, stats.total() / 30.0), (int) stats.total(), LEARNER_KEY, 1, now, null, now));
        }
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}
