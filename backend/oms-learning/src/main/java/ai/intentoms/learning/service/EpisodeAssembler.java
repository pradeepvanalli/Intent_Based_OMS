package ai.intentoms.learning.service;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.platform.ids.Ids;
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
 * OC-02: opens one episode per decision point, joined to everything that follows it. Listens for the same
 * {@code decision.evaluated} event every decision already emits, so no decision path needs to know learning
 * exists. Only LIVE-mode decisions become episodes — shadow/simulation/replay evaluations aren't real outcomes to
 * learn from.
 */
@Component
public class EpisodeAssembler implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(EpisodeAssembler.class);

    private final DecisionRecordRepository decisionRecords;
    private final EpisodeRepository episodes;

    public EpisodeAssembler(DecisionRecordRepository decisionRecords, EpisodeRepository episodes) {
        this.decisionRecords = decisionRecords;
        this.episodes = episodes;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("decision.evaluated"); }

    @Override
    public void on(Event e) {
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            Optional<DecisionRecord> rec = decisionRecords.find(e.aggregateId());
            if (rec.isEmpty() || rec.get().mode() != Mode.LIVE) return;
            if (episodes.findByDecisionId(rec.get().id()).isPresent()) return; // idempotent re-delivery
            DecisionRecord r = rec.get();
            String orderId = deriveOrderId(r, e);
            Episode episode = new Episode(Ids.prefixed("epi"), r.id(), r.decision(), orderId, lineIdOf(r), fulfillmentGroupIdOf(r),
                EpisodeStatus.OPEN, null, Map.of(), r.policyVersion(), Instant.now(), null);
            episodes.insert(episode);
        } catch (RuntimeException ex) {
            log.warn("Episode assembly failed for decision record {}: {}", e.aggregateId(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    /** Best-effort order id: the decision's own subject when it IS an order, else its correlation id, which every
     * order-scoped decision call sets to the order id by convention. Refine per-decision if a future decision's
     * subject/correlation shape doesn't follow this. */
    private static String deriveOrderId(DecisionRecord r, Event e) {
        if ("order".equals(r.subjectType())) return r.subjectId();
        if (e.correlationId() != null) return e.correlationId();
        return "order".equals(e.aggregateType()) ? e.aggregateId() : null;
    }

    private static String lineIdOf(DecisionRecord r) { return "line".equals(r.subjectType()) ? r.subjectId() : null; }

    private static String fulfillmentGroupIdOf(DecisionRecord r) {
        Object fg = r.input() == null ? null : r.input().get("fulfillmentGroupId");
        return fg == null ? null : String.valueOf(fg);
    }
}
