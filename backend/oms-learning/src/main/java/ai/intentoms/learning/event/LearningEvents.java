package ai.intentoms.learning.event;

import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GV-04: every learning governance transition is a ledger event (aggregate type {@code learning}) in the same
 * dotted-past-tense convention as the rest of the ledger, each carrying a SHA-256 digest of its own payload so the
 * event is tamper-evident on its own, besides the whole-ledger digest the evidence pack computes. Covered: the eight
 * §9.2 lifecycle events, proposal approval and rejection, objective drafts and approvals, learner autonomy, on/off,
 * pause and resume, the tenant kill switch, live-use promotion and revocation of learned parameters, digests,
 * retention runs and crypto-shred propagation. {@code proposal.drafted} and {@code hypothesis.raised} are written
 * once per aggregate, whichever writer (repository or LLM service) gets there first.
 */
@Component
public class LearningEvents {
    public static final String AGGREGATE = "learning";

    private final EventStore events;
    public LearningEvents(EventStore events) { this.events = events; }

    public void episodeMatured(String episodeId, Actor actor, Map<String, Object> payload) { append(episodeId, "episode.matured", actor, payload); }
    public void factLearned(String factId, Actor actor, Map<String, Object> payload) { append(factId, "fact.learned", actor, payload); }
    public void hypothesisRaised(String hypothesisId, Actor actor, Map<String, Object> payload) { appendOnce(hypothesisId, "hypothesis.raised", actor, payload); }
    public void proposalDrafted(String proposalId, Actor actor, Map<String, Object> payload) { appendOnce(proposalId, "proposal.drafted", actor, payload); }
    public void proposalEvaluated(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.evaluated", actor, payload); }
    public void experimentStarted(String experimentId, Actor actor, Map<String, Object> payload) { append(experimentId, "experiment.started", actor, payload); }
    public void proposalPromoted(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.promoted", actor, payload); }
    public void proposalRolledBack(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.rolled_back", actor, payload); }
    public void proposalApproved(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.approved", actor, payload); }
    public void proposalRejected(String proposalId, Actor actor, Map<String, Object> payload) { append(proposalId, "proposal.rejected", actor, payload); }

    public void objectiveDrafted(int version, Actor actor, Map<String, Object> payload) { append("objective:" + version, "objective.drafted", actor, payload); }
    public void objectiveApproved(int version, Actor actor, Map<String, Object> payload) { append("objective:" + version, "objective.approved", actor, payload); }

    public void learnerAutonomyChanged(String learnerKey, Actor actor, Map<String, Object> payload) { append("learner:" + learnerKey, "learner.autonomy_changed", actor, payload); }
    public void learnerSwitched(String learnerKey, Actor actor, Map<String, Object> payload) { append("learner:" + learnerKey, "learner.switched", actor, payload); }
    public void learnerPaused(String learnerKey, Actor actor, Map<String, Object> payload) { append("learner:" + learnerKey, "learner.paused", actor, payload); }
    public void learnerResumed(String learnerKey, Actor actor, Map<String, Object> payload) { append("learner:" + learnerKey, "learner.resumed", actor, payload); }
    public void tenantKillSwitch(Actor actor, Map<String, Object> payload) { append("tenant-kill-switch", "learning.kill_switch_changed", actor, payload); }
    /** GV-03 global level: recorded in the acting tenant's ledger, since the switch itself is tenant-independent. */
    public void globalKillSwitch(Actor actor, Map<String, Object> payload) { append("global-kill-switch", "learning.global_kill_switch_changed", actor, payload); }
    public void learnerBudgetChanged(String learnerKey, Actor actor, Map<String, Object> payload) { append("learner:" + learnerKey, "learner.budget_changed", actor, payload); }
    /** §5: a rejected or rolled-back change returns to its hypothesis as evidence against it. */
    public void hypothesisCounterEvidence(String hypothesisId, Actor actor, Map<String, Object> payload) { append(hypothesisId, "hypothesis.counter_evidence", actor, payload); }
    public void liveParameterChanged(String parameter, Actor actor, Map<String, Object> payload) { append("parameter:" + parameter, "parameter.live_changed", actor, payload); }

    public void digestPublished(String digestId, Actor actor, Map<String, Object> payload) { append(digestId, "learning.digest_published", actor, payload); }
    public void retentionRun(Actor actor, Map<String, Object> payload) { append("retention", "learning.retention_run", actor, payload); }
    public void shredPropagated(Actor actor, Map<String, Object> payload) { append("crypto-shred", "learning.shred_propagated", actor, payload); }

    private void appendOnce(String aggregateId, String type, Actor actor, Map<String, Object> payload) {
        boolean seen = events.load(AGGREGATE, aggregateId).stream().anyMatch(e -> type.equals(e.type()));
        if (!seen) append(aggregateId, type, actor, payload);
    }

    private void append(String aggregateId, String type, Actor actor, Map<String, Object> payload) {
        Map<String, Object> withDigest = new LinkedHashMap<>(payload);
        withDigest.put("payloadDigest", Json.sha256(payload));
        events.append(AGGREGATE, aggregateId, new NewEvent(type, withDigest, actor));
    }
}
