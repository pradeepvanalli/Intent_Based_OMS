package ai.intentoms.learning.experiment;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.ExperimentRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * EX-02 (shadow), EX-03 (canary), EX-04 (persistent holdout), EX-06 (one experiment per artifact per slice).
 *
 * <p>For a {@code decision:} target, shadow mode reuses the decision fabric's own {@code Mode.SHADOW} — running a
 * decision version in shadow is already a first-class, already-built capability of {@code DecisionRuntime}; this
 * class's job is the experiment bookkeeping (who's running, for how long, against what), not reimplementing shadow
 * evaluation. Driving an actual shadow decision-definition version is a {@code DecisionStudioService.publish(name,
 * version, "shadow", ...)} call once a proposal has a draft version — left as the documented integration point for
 * whichever caller creates the draft (the promotion/evaluation path), since this class only owns the experiment
 * record's lifecycle, not decision-definition drafting.
 */
@Service
public class ExperimentManager {
    private static final double DEFAULT_HOLDOUT_SHARE = 0.05;
    private static final Duration MIN_CANARY_DURATION = Duration.ofDays(2);

    private final ExperimentRepository experiments;
    private final ProposalRepository proposals;
    private final LearningEvents events;
    private final AuthorizationService authz;

    public ExperimentManager(ExperimentRepository experiments, ProposalRepository proposals, LearningEvents events, AuthorizationService authz) {
        this.experiments = experiments;
        this.proposals = proposals;
        this.events = events;
        this.authz = authz;
    }

    /** EX-06: only one running experiment per target artifact — checked across every proposal for that artifact,
     * not just the one being started. */
    public Experiment start(String proposalId, ExperimentMode mode, double trafficShare, Map<String, Object> stopRule) {
        Proposal p = proposals.get(proposalId);
        String target = p.changeset().target().toString();
        for (Proposal sibling : proposals.byStatus("CANARY", 200)) {
            if (!sibling.changeset().target().toString().equals(target)) continue;
            if (experiments.activeFor(sibling.proposalId()).isPresent()) {
                throw new ConflictException("An experiment is already running for " + target + " (proposal " + sibling.proposalId() + ") — EX-06 allows only one per artifact per slice");
            }
        }
        if (experiments.activeFor(proposalId).isPresent()) throw new ConflictException("Proposal " + proposalId + " already has a running experiment");
        Instant now = Instant.now();
        Experiment e = new Experiment(Ids.prefixed("exp"), proposalId, mode, trafficShare, ExperimentStatus.RUNNING, now,
            mode == ExperimentMode.HOLDOUT ? null : now.plus(MIN_CANARY_DURATION), stopRule == null ? Map.of() : stopRule, null, now);
        experiments.insert(e);
        if (mode != ExperimentMode.SHADOW) proposals.setStatus(proposalId, ai.intentoms.learning.model.ProposalStatus.CANARY, now);
        events.experimentStarted(e.experimentId(), Actor.parse(authz.requirePrincipal().actorRef()), Map.of("proposalId", proposalId, "mode", mode.name(), "trafficShare", trafficShare));
        return e;
    }

    /** EX-04: the tenant's persistent holdout — a continuously-running CANARY with no end date, kept out of every
     * promoted change. Not tied to any one proposal's lifecycle; its {@code proposalId} is a sentinel. */
    public Experiment ensureHoldout(double share) {
        return experiments.activeFor(HOLDOUT_SENTINEL).orElseGet(() -> {
            Instant now = Instant.now();
            Experiment e = new Experiment(Ids.prefixed("exp"), HOLDOUT_SENTINEL, ExperimentMode.HOLDOUT, share <= 0 ? DEFAULT_HOLDOUT_SHARE : share,
                ExperimentStatus.RUNNING, now, null, Map.of(), null, now);
            experiments.insert(e);
            events.experimentStarted(e.experimentId(), Actor.system("experiment-manager"), Map.of("mode", "HOLDOUT", "trafficShare", e.trafficShare()));
            return e;
        });
    }
    private static final String HOLDOUT_SENTINEL = "tenant-holdout";

    public void stop(String experimentId, ExperimentStatus status, Map<String, Object> result) {
        experiments.stop(experimentId, status, result, Instant.now());
    }

    /** EX-03: sticky per-customer routing — the same customer always lands in the same arm (control vs. the
     * proposed policy) for the life of one experiment, via a deterministic hash rather than per-request randomness.
     * Returns true if the customer is routed into the experiment's treatment arm. */
    public static boolean stickyInTreatment(String experimentId, String customerId, double trafficShare) {
        if (customerId == null || customerId.isBlank() || trafficShare <= 0) return false;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((experimentId + ":" + customerId).getBytes(StandardCharsets.UTF_8));
            long bucket = ((d[0] & 0xFF) << 8 | (d[1] & 0xFF)) % 10_000L; // 0..9999
            return bucket < Math.round(trafficShare * 10_000);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** EX-03: sequential-testing early stop — a lightweight check, not a full sequential probability ratio test:
     * once a minimum sample is in and the observed effect's sign is stable and large relative to its own noise,
     * stop early rather than waiting out the full canary duration. Real SPRT is a reasonable follow-up; this is a
     * defensible, documented approximation given everything else in scope. */
    public boolean shouldStopEarly(Experiment e, double observedLift, double observedStdError, int sampleSize, int minSample) {
        if (e.mode() != ExperimentMode.CANARY || sampleSize < minSample) return false;
        double z = observedStdError <= 0 ? 0 : observedLift / observedStdError;
        return Math.abs(z) >= 2.58; // ~99% confidence the sign is real, not noise
    }

    public List<Experiment> running() { return experiments.running(); }
}
