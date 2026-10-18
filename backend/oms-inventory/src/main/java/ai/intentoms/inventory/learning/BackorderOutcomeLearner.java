package ai.intentoms.inventory.learning;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IU-06: learns from how backorders end. Every order-line transition out of {@code backordered} is counted once per
 * ledger event: on to {@code cancelled} is a lost sale, anything else (back to sourcing once stock arrives) a kept one.
 * The per-SKU kept share, shrunk toward 0.5 with weight 5, is written as {@code BACKORDER_CONVERSION_RATE} (subject
 * {@code SKU}); the backorder decision's input carries it so a person can see how often customers wait for this item.
 * Counting continues while the learner is paused (the history is data); only the fact write follows the gate.
 */
@Component
public class BackorderOutcomeLearner implements EventSubscriber, GovernedLearner {
    public static final String KEY = "inventory_backorder";
    public static final String BACKORDERED = "backordered";
    private static final Logger log = LoggerFactory.getLogger(BackorderOutcomeLearner.class);
    static final double PRIOR = 0.5;
    static final double PRIOR_WEIGHT = 5;

    private final InventoryLearningRepository repo;
    private final InventoryLearnerSupport support;
    private final LearnerGovernance governance;

    public BackorderOutcomeLearner(InventoryLearningRepository repo, InventoryLearnerSupport support, LearnerGovernance governance) {
        this.repo = repo;
        this.support = support;
        this.governance = governance;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("order.transitioned"); }

    /** The (orderId, lineId, outcome) of a transition out of backordered, or empty for any other transition. */
    public record Resolution(String orderId, String lineId, String outcome) {}

    public static Optional<Resolution> resolutionOf(Event e) {
        Map<String, Object> p = e.payload();
        if (p == null || !BACKORDERED.equals(p.get("from")) || !"order_line".equals(p.get("entityType"))) return Optional.empty();
        String entityId = String.valueOf(p.get("entityId"));
        int sep = entityId.indexOf(':');
        String orderId = sep > 0 ? entityId.substring(0, sep) : e.aggregateId();
        String lineId = sep > 0 ? entityId.substring(sep + 1) : null;
        return Optional.of(new Resolution(orderId, lineId, "cancelled".equals(p.get("to")) ? "cancelled" : "converted"));
    }

    @Override
    public void on(Event e) {
        Optional<Resolution> res = resolutionOf(e);
        if (res.isEmpty()) return;
        var previous = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            Resolution r = res.get();
            String sku = r.lineId() == null ? null : repo.skuOfLine(r.orderId(), r.lineId()).orElse(null);
            if (!repo.recordBackorderResolution(e.id(), r.orderId(), r.lineId(), sku, r.outcome(), e.occurredAt() == null ? Instant.now() : e.occurredAt())) return;
            if (sku == null || !governance.begin(KEY).mayRun()) return;
            governance.recordRunThrottled(KEY);
            repo.backorderStat(sku).ifPresent(s -> {
                int n = s.converted() + s.cancelled();
                double rate = (s.converted() + PRIOR * PRIOR_WEIGHT) / (n + PRIOR_WEIGHT);
                support.writeNumber(SubjectType.SKU, sku, FactKey.BACKORDER_CONVERSION_RATE, rate, Math.min(1.0, n / 30.0), n, KEY, 0.0);
            });
        } catch (RuntimeException ex) {
            log.warn("Backorder outcome learning failed for event {}: {}", e.id(), ex.toString());
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Backorder outcomes", "Learns, per item, how often customers wait for a backorder instead of cancelling.",
            LearnerDescriptor.Loop.FAST, "IU-06").features("order.transitioned", "order_line.sku");
    }
}
