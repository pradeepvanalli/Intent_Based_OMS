package ai.intentoms.learning.service;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.DecisionPoints;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * OC-02: one episode per decision point (decision, subject type, subject id, line, fulfilment group), joined to
 * everything that follows it. Listens for the {@code decision.evaluated} event every decision already emits, so no
 * decision path needs to know learning exists. Only LIVE evaluations count; a re-evaluation of the same point (a
 * scheduled scorer re-scoring an open order) updates the episode's latest decision instead of opening another.
 * Decisions in {@code oms.learning.episode.excluded-decisions} (default: the injection screen, which is a guard on
 * text, not a decision with a business outcome) never open episodes.
 */
@Component
public class EpisodeAssembler implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(EpisodeAssembler.class);

    private final DecisionRecordRepository decisionRecords;
    private final EpisodeRepository episodes;
    private final Set<String> excluded;

    public EpisodeAssembler(DecisionRecordRepository decisionRecords, EpisodeRepository episodes,
                            @Value("${oms.learning.episode.excluded-decisions:injection_screen}") List<String> excluded) {
        this.decisionRecords = decisionRecords;
        this.episodes = episodes;
        this.excluded = Set.copyOf(excluded);
    }

    @Override public List<String> eventTypePrefixes() { return List.of("decision.evaluated"); }

    @Override
    public void on(Event e) {
        var previousTenant = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            Optional<DecisionRecord> rec = decisionRecords.find(e.aggregateId());
            if (rec.isEmpty() || rec.get().mode() != Mode.LIVE || excluded.contains(rec.get().decision())) return;
            DecisionRecord r = rec.get();
            episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), r.id(), r.decision(), DecisionPoints.orderId(r, e.correlationId()),
                DecisionPoints.lineId(r), DecisionPoints.fulfillmentGroupId(r), r.policyVersion(), r.subjectType(), r.subjectId(), r.createdAt()));
        } catch (RuntimeException ex) {
            log.warn("Episode assembly failed for decision record {}: {}", e.aggregateId(), ex.toString());
        } finally {
            previousTenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
