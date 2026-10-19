package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OC-03: turns ledger events into the typed outcome catalog through the registered {@link OutcomeMapper}s, and
 * attaches each outcome to every open or provisional episode it concerns — the episodes of the event's order and
 * the episodes whose decision was about the event's own aggregate (a return's events reach the return-risk decision
 * even though they carry no order id). One outcome per (event, episode): a redelivered event changes nothing.
 */
@Component
public class OutcomeObserver implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(OutcomeObserver.class);

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final Map<String, List<OutcomeMapper>> byType = new LinkedHashMap<>();

    private final GroupLineage lineage;

    public OutcomeObserver(EpisodeRepository episodes, OutcomeRepository outcomes, List<OutcomeMapper> mappers, GroupLineage lineage) {
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.lineage = lineage;
        for (OutcomeMapper m : mappers) for (String t : m.eventTypes()) byType.computeIfAbsent(t, k -> new ArrayList<>()).add(m);
    }

    @Override public List<String> eventTypePrefixes() { return List.copyOf(byType.keySet()); }

    @Override
    public void on(Event e) {
        List<OutcomeMapper> mappers = byType.get(e.type());
        if (mappers == null) return;
        var previousTenant = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            for (OutcomeMapper mapper : mappers) {
                OutcomeData data = mapper.map(e);
                if (data != null) attach(e, data);
            }
        } catch (RuntimeException ex) {
            log.warn("Outcome observation failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            previousTenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private void attach(Event e, OutcomeData data) {
        Map<String, Episode> targets = new LinkedHashMap<>();
        String orderId = orderIdOf(e);
        if (orderId != null) episodes.forOrder(orderId).forEach(ep -> targets.put(ep.episodeId(), ep));
        if (e.aggregateType() != null && !"order".equals(e.aggregateType()))
            episodes.forSubject(e.aggregateType(), e.aggregateId()).forEach(ep -> targets.put(ep.episodeId(), ep));
        // OC-02 (A-N1): a group's outcome belongs to the decision point that planned the group (GroupLineage).
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        // A refused group (order.resourced) is routed by that group, and only to the decision point that planned it: other
        // decisions' order-level episodes keep their own view of the order's units.
        boolean refusal = CoreOutcomeMapper.isRefusal(data);
        Object group = refusal ? ((OutcomeData.FulfillmentOutcome) data).fulfilmentGroupId() : p.getOrDefault("fulfilmentGroupId", p.get("fulfillmentGroupId"));
        Map<String, Boolean> planned = lineage.owners(orderId, group, targets.values());
        for (Episode ep : targets.values()) {
            if (refusal && !Boolean.TRUE.equals(planned.get(ep.episodeId()))) continue;
            if (ep.status() == EpisodeStatus.MATURE || !planned.getOrDefault(ep.episodeId(), concerns(e, ep))) continue;
            if (data instanceof OutcomeData.ReturnOutcome r && r.rmaId() != null && mergeIntoReturn(ep, r)) continue;
            if (outcomes.insert(OutcomeRecord.of(Ids.prefixed("out"), ep.episodeId(), data, e.occurredAt(), e.id(), Instant.now()))) episodes.outcomeChanged(ep.episodeId());
        }
    }

    /**
     * One outcome per return and episode (audit A2): a later event of a return already recorded on this episode (its
     * refund after its disposition, a person's disposition after the automatic one) updates that outcome instead of
     * adding a second loss. The merge is idempotent, so a redelivered event changes nothing.
     */
    private boolean mergeIntoReturn(Episode ep, OutcomeData.ReturnOutcome r) {
        for (OutcomeRecord existing : outcomes.forEpisode(ep.episodeId())) {
            if (existing.payload() instanceof OutcomeData.ReturnOutcome prior && r.rmaId().equals(prior.rmaId())) {
                OutcomeData.ReturnOutcome merged = prior.merge(r);
                if (!merged.equals(prior)) {
                    outcomes.updatePayload(existing.outcomeId(), merged);
                    episodes.outcomeChanged(ep.episodeId());
                }
                return true;
            }
        }
        return false;
    }

    /**
     * OC-02: an outcome that names a fulfilment group or lines only reaches episodes for that group or those lines; an
     * order-level episode (no group, no line) receives every outcome of the order. A package shipped from one node
     * therefore never scores the decision made for another package of the same order.
     */
    static boolean concerns(Event e, Episode ep) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        Object fg = p.getOrDefault("fulfilmentGroupId", p.get("fulfillmentGroupId"));
        if (ep.fulfillmentGroupId() != null && fg != null && !ep.fulfillmentGroupId().equals(String.valueOf(fg))) return false;
        if (ep.lineId() != null) {
            java.util.Set<String> lines = new java.util.HashSet<>();
            if (p.get("lineId") != null) lines.add(String.valueOf(p.get("lineId")));
            if (p.get("lineIds") instanceof java.util.Collection<?> c) c.forEach(x -> lines.add(String.valueOf(x)));
            if (p.get("lines") instanceof Map<?, ?> m) m.keySet().forEach(x -> lines.add(String.valueOf(x)));
            if (!lines.isEmpty() && !lines.contains(ep.lineId())) return false;
        }
        return true;
    }

    static String orderIdOf(Event e) {
        if (e.payload() != null) {
            Object o = e.payload().getOrDefault("orderId", e.payload().get("order_id"));
            if (o != null) return String.valueOf(o);
        }
        return "order".equals(e.aggregateType()) ? e.aggregateId() : e.correlationId();
    }
}
