package ai.intentoms.inventory.learning;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.hooks.BackorderFeatures;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IU-06: learns from how backorders end. Every order-line transition out of {@code backordered} is counted once per
 * ledger event: on to {@code cancelled} is a lost sale, anything else (back to sourcing once stock arrives) a kept one.
 * The per-SKU kept share, shrunk toward 0.5 with weight 5, is written as {@code BACKORDER_CONVERSION_RATE} (subject
 * {@code SKU}).
 *
 * <p>Each resolution is linked to the {@code backorder_next_action} decision that chose what to do with the line (the
 * latest one for the order and SKU made before the line resolved): the decision record gets a
 * {@code backorder_outcome} label and the resolution keeps the chosen option. The kept share per option, across SKUs,
 * becomes {@code BACKORDER_CONVERSION_RATE} for subject DECISION {@code backorder_next_action|<option>}.
 * {@code BackorderFeatures} feeds both back into the decision ({@code line.backorder_conversion_rate},
 * {@code history.option_kept_rate}) once {@code inventory.backorder_conversion} is promoted for live use.
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
    private final DecisionRecordRepository decisionRecords;

    public BackorderOutcomeLearner(InventoryLearningRepository repo, InventoryLearnerSupport support, LearnerGovernance governance, DecisionRecordRepository decisionRecords) {
        this.repo = repo;
        this.support = support;
        this.governance = governance;
        this.decisionRecords = decisionRecords;
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
            Instant at = e.occurredAt() == null ? Instant.now() : e.occurredAt();
            String sku = r.lineId() == null ? null : repo.skuOfLine(r.orderId(), r.lineId()).orElse(null);
            if (!repo.recordBackorderResolution(e.id(), r.orderId(), r.lineId(), sku, r.outcome(), at)) return;
            Optional<DecisionRecord> chose = choosingDecision(r.orderId(), sku, at);
            chose.ifPresent(d -> link(e.id(), d, r, sku, at));
            if (!governance.begin(KEY).mayRun()) return;
            governance.recordRunThrottled(KEY);
            if (sku != null) repo.backorderStat(sku).ifPresent(s -> support.writeNumber(SubjectType.SKU, sku, FactKey.BACKORDER_CONVERSION_RATE, keptRate(s),
                Math.min(1.0, (s.converted() + s.cancelled()) / 30.0), s.converted() + s.cancelled(), KEY, 0.0));
            if (chose.isPresent()) {
                for (InventoryLearningRepository.BackorderStat s : repo.backorderOptionStats()) {
                    int n = s.converted() + s.cancelled();
                    support.writeNumber(SubjectType.DECISION, BackorderFeatures.optionSubject(s.sku()), FactKey.BACKORDER_CONVERSION_RATE, keptRate(s), Math.min(1.0, n / 30.0), n, KEY, 0.0);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Backorder outcome learning failed for event {}: {}", e.id(), ex.toString());
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    static double keptRate(InventoryLearningRepository.BackorderStat s) {
        int n = s.converted() + s.cancelled();
        return (s.converted() + PRIOR * PRIOR_WEIGHT) / (n + PRIOR_WEIGHT);
    }

    /** The latest backorder decision for this order (and SKU, when the record shows one) made before the line resolved. */
    Optional<DecisionRecord> choosingDecision(String orderId, String sku, Instant resolvedAt) {
        for (DecisionRecord d : decisionRecords.forSubject("order", orderId)) {
            if (!BackorderFeatures.DECISION.equals(d.decision()) || d.createdAt() == null || d.createdAt().isAfter(resolvedAt)) continue;
            Object recordSku = d.input() != null && d.input().get("line") instanceof Map<?, ?> line ? line.get("sku") : null;
            if (sku != null && recordSku != null && !sku.equals(String.valueOf(recordSku))) continue;
            if (option(d) != null) return Optional.of(d);
        }
        return Optional.empty();
    }

    private void link(String eventId, DecisionRecord d, Resolution r, String sku, Instant at) {
        String option = option(d);
        repo.linkBackorderResolution(eventId, d.id(), option);
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("outcome", r.outcome());
        label.put("chosenOption", option);
        if (r.lineId() != null) label.put("lineId", r.lineId());
        if (sku != null) label.put("sku", sku);
        label.put("resolvedAt", at.toString());
        label.put("eventId", eventId);
        decisionRecords.label(d.id(), "backorder_outcome", label);
    }

    /** The option the decision settled on (after any human override). */
    static String option(DecisionRecord d) {
        Object v = d.effectiveValue();
        if (v instanceof Map<?, ?> m) v = m.containsKey("next_action") ? m.get("next_action") : m.values().stream().findFirst().orElse(null);
        if (v == null) return null;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        return s.matches("[a-z_]{1,40}") && !"unclear".equals(s) ? s : null;
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Backorder outcomes", "Learns, per item, how often customers wait for a backorder instead of cancelling.",
            LearnerDescriptor.Loop.FAST, "IU-06").features("order.transitioned", "order_line.sku").readsOutcomesOf(BackorderFeatures.DECISION)
            .feeds(BackorderFeatures.PARAMETER);
    }
}
