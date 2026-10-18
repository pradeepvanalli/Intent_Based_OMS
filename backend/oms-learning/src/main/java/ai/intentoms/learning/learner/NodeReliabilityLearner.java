package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * FS-02: a Beta-binomial posterior per node x sku_class x day_part, fed directly from ledger events (fast loop, no
 * LLM) — updates the reliability stats incrementally on every relevant event, then republishes the current
 * posterior mean as {@code NODE_ON_TIME_RATE}/{@code NODE_REJECT_RATE} learned facts whenever it moves, so {@code
 * sourcing_node_score} (via {@code SoftScorer}) always reads a fresh value once a node has 10+ samples.
 *
 * <p>On-time is computed by comparing the delivery event's own timestamp against the ETA promised at sourcing time
 * ({@code order.sourced}'s {@code packages[].eta}, captured in {@code node_assignment}) — neither {@code
 * order.shipped} nor {@code order.delivered} carries on-time/late data itself (confirmed by reading
 * StandardShipOrderWorkflow directly), which is also a real gap in OutcomeObserver's DeliveryOutcome mapping: it
 * reads a payload key ({@code onTime}) that's never actually set, so it silently defaults to true today. Flagging
 * for the parent to fix in OutcomeObserver — out of this fork's file-ownership boundary to patch directly.
 *
 * <p>Day-part is kept coarse (morning/afternoon/evening/night from the event's own clock hour); sku_class is
 * "default" until order line data is wired in — oms-learning has no oms-order dependency to resolve a real SKU
 * class today, so every line is pooled together.
 */
@Component
public class NodeReliabilityLearner implements EventSubscriber, GovernedLearner {
    public static final String KEY = "node_reliability";
    private static final Logger log = LoggerFactory.getLogger(NodeReliabilityLearner.class);
    private static final String LEARNER_KEY = "node_reliability";
    static final String SKU_CLASS = "default";

    private final NodeAssignmentRepository assignments;
    private final NodeReliabilityStatRepository stats;
    private final LearnedFactRepository facts;

    private final LearnerGovernance governance;

    public NodeReliabilityLearner(NodeAssignmentRepository assignments, NodeReliabilityStatRepository stats, LearnedFactRepository facts, LearnerGovernance governance) {
        this.governance = governance;
        this.assignments = assignments;
        this.stats = stats;
        this.facts = facts;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("order.sourced", "order.resourced", "order.delivered", "event.delivered"); }

    @Override
    public void on(Event e) {
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            if (!governance.begin(KEY).mayRun()) return;
            governance.recordRunThrottled(KEY);
            switch (e.type()) {
                case "order.sourced" -> onSourced(e);
                case "order.resourced" -> onResourced(e);
                case "order.delivered", "event.delivered" -> onDelivered(e);
                default -> {}
            }
        } catch (RuntimeException ex) {
            log.warn("Node reliability learning failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private void onSourced(Event e) {
        Object packages = e.payload() == null ? null : e.payload().get("packages");
        if (!(packages instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> pkg) || pkg.get("nodeId") == null) continue;
            LocalDate eta = parseDate(pkg.get("eta"));
            assignments.record(e.aggregateId(), String.valueOf(pkg.get("nodeId")), SKU_CLASS, eta, e.occurredAt());
        }
    }

    @SuppressWarnings("unchecked")
    private void onResourced(Event e) {
        Object rejected = e.payload() == null ? null : e.payload().get("rejectedNodes");
        if (!(rejected instanceof List<?> list)) return;
        String dayPart = dayPart(e.occurredAt());
        for (Object n : list) {
            String nodeId = String.valueOf(n);
            stats.recordReject(nodeId, SKU_CLASS, dayPart, true);
            publishFacts(nodeId);
        }
    }

    private void onDelivered(Event e) {
        String dayPart = dayPart(e.occurredAt());
        LocalDate deliveredOn = e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        for (NodeAssignmentRepository.Assignment a : assignments.assignmentsFor(e.aggregateId())) {
            boolean onTime = a.promisedEta() == null || !deliveredOn.isAfter(a.promisedEta());
            stats.recordOnTime(a.nodeId(), SKU_CLASS, dayPart, onTime);
            stats.recordReject(a.nodeId(), SKU_CLASS, dayPart, false);
            publishFacts(a.nodeId());
        }
    }

    private void publishFacts(String nodeId) {
        var posterior = stats.posterior(nodeId, SKU_CLASS, dayPart(Instant.now()));
        Instant now = Instant.now();
        facts.supersede(SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE,
            new TypedValue.NumberValue(posterior.onTimeMean()), Math.min(1.0, posterior.sampleSize() / 30.0), posterior.sampleSize(), LEARNER_KEY, 1, now, null, now));
        facts.supersede(SubjectType.NODE, nodeId, FactKey.NODE_REJECT_RATE, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, nodeId, FactKey.NODE_REJECT_RATE,
            new TypedValue.NumberValue(posterior.rejectMean()), Math.min(1.0, posterior.sampleSize() / 30.0), posterior.sampleSize(), LEARNER_KEY, 1, now, null, now));
    }

    static String dayPart(Instant at) {
        int hour = at.atZone(ZoneOffset.UTC).getHour();
        if (hour < 6) return "night";
        if (hour < 12) return "morning";
        if (hour < 18) return "afternoon";
        return "evening";
    }

    static LocalDate parseDate(Object v) {
        if (v == null) return null;
        try { return LocalDate.parse(String.valueOf(v).length() > 10 ? String.valueOf(v).substring(0, 10) : String.valueOf(v)); }
        catch (RuntimeException e) { return null; }
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Node reliability", "Learns how often each store or warehouse ships on time and how often it rejects orders.", LearnerDescriptor.Loop.FAST, "FS-02").readsOutcomesOf("sourcing_node_score").feeds(ai.intentoms.learning.hooks.NodeReliabilityFeatures.REJECT_RATE, ai.intentoms.learning.hooks.NodeReliabilityFeatures.ON_TIME_RATE);
    }
}
