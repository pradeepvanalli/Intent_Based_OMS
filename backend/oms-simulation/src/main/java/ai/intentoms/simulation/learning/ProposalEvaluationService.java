package ai.intentoms.simulation.learning;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EX-01: runs {@link OffPolicyEvaluator} against a proposal's target decision using up to 90 days of logged LIVE
 * records whose episode has matured enough to carry a reward, then writes the result as the proposal's evaluation
 * report. Only proposals targeting a {@code decision:...} artifact are supported today — a proposal targeting a
 * {@code parameter:...} artifact (a fast-loop learner's own numeric parameter) doesn't have a decision_record trail
 * to replay and needs a different evaluation path once that learner build defines one.
 */
@Service
public class ProposalEvaluationService {
    private static final Duration LOOKBACK = Duration.ofDays(90);
    private static final int MAX_RECORDS = 5000;

    private final ProposalRepository proposals;
    private final DecisionRecordRepository decisionRecords;
    private final EpisodeRepository episodes;
    private final LearningEvents events;

    public ProposalEvaluationService(ProposalRepository proposals, DecisionRecordRepository decisionRecords, EpisodeRepository episodes, LearningEvents events) {
        this.proposals = proposals;
        this.decisionRecords = decisionRecords;
        this.episodes = episodes;
        this.events = events;
    }

    public Proposal evaluate(String proposalId, String actorRef) {
        Proposal proposal = proposals.get(proposalId);
        ArtifactRef target = proposal.changeset().target();
        if (!"decision".equals(target.type())) {
            throw new ValidationException("Replay evaluation only supports decision targets today; got " + target);
        }

        List<DecisionRecord> records = decisionRecords.recentLive(target.name(), proposal.changeset().baseVersion(), Instant.now().minus(LOOKBACK), MAX_RECORDS);
        List<LoggedRecord> logged = records.stream()
            .map(this::toLoggedRecord)
            .flatMap(Optional::stream)
            .toList();

        OffPolicyEvaluator.Result result = OffPolicyEvaluator.evaluate(logged, proposal.changeset());
        Map<String, Object> report = result.asMap();

        proposals.markEvaluated(proposalId, report, Instant.now());
        events.proposalEvaluated(proposalId, Actor.parse(actorRef), report);
        return proposals.get(proposalId);
    }

    /** Pairs a decision record with its episode's reward; only episodes past OPEN carry a usable reward (OC-04). */
    private Optional<LoggedRecord> toLoggedRecord(DecisionRecord r) {
        Optional<Episode> ep = episodes.findByDecisionId(r.id());
        if (ep.isEmpty() || ep.get().reward() == null) return Optional.empty();
        String arm = r.chosenArm() != null ? r.chosenArm() : String.valueOf(r.value());
        return Optional.of(new LoggedRecord(arm, r.propensity(), r.confidence(), r.band().name(), ep.get().reward()));
    }
}
