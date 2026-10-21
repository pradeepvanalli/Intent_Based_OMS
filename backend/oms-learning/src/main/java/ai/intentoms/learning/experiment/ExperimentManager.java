package ai.intentoms.learning.experiment;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DefinitionCache;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.promotion.ChangesetApplier;
import ai.intentoms.learning.promotion.SystemPrincipal;
import ai.intentoms.learning.store.ExperimentRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EX-02 shadow, EX-03 canary, EX-04 persistent holdout and EX-06 one experiment per artifact per slice.
 *
 * <ul>
 *   <li><b>Shadow</b> of a decision change: the changeset is applied to the live YAML and published as a real shadow
 *       version, which the decision runtime evaluates alongside every live call without acting. On completion the shadow
 *       records are paired with the live ones (agreement, escalations, post-check failures, latency) and the verdict is
 *       written to the proposal as {@code evaluationReport.shadow}, which the promotion gate reads for T0 at L1. Shadow of
 *       a {@code parameter:live:<family>} change measures how many live decisions held back a learned value.</li>
 *   <li><b>Canary</b> of a decision change: the candidate version is created as a studio draft that must lint and pass
 *       its golden set; it is never published. {@code ExperimentTrafficRouter} sends a sticky share of customers to it
 *       through the decision runtime's learning hook. An always-valid sequential test ({@link Msprt}) on episode rewards
 *       decides when to stop.</li>
 *   <li><b>Holdout</b>: one per tenant, a sticky share kept on each decision's pre-learning baseline version.</li>
 * </ul>
 */
@Service
public class ExperimentManager {
    private static final Logger log = LoggerFactory.getLogger(ExperimentManager.class);
    public static final String HOLDOUT_TARGET = "tenant";
    static final String HOLDOUT_SENTINEL = "tenant-holdout";
    private static final double DEFAULT_HOLDOUT_SHARE = 0.05;
    private static final Duration DEFAULT_SHADOW_DURATION = Duration.ofDays(3);
    private static final Duration DEFAULT_CANARY_DURATION = Duration.ofDays(7);
    private static final Duration MAX_CANARY_DURATION = Duration.ofDays(28);
    /**
     * D-5 (EX-03): the tenant's minimum canary duration in days, both the default and the floor a starter cannot go
     * below (default 7; the tenant itself cannot set less than {@link #CANARY_MIN_DAYS_FLOOR}).
     */
    public static final String CANARY_MIN_DAYS_KEY = "learning.canary.min_duration_days";
    static final int DEFAULT_CANARY_MIN_DAYS = 7;
    static final int CANARY_MIN_DAYS_FLOOR = 1;
    /**
     * D-5 (EX-02): the tenant's minimum number of paired (or affected) decisions before a shadow can pass, both the
     * default and the floor a starter cannot go below (default 30; never below {@link #SHADOW_MIN_RECORDS_FLOOR}).
     */
    public static final String SHADOW_MIN_RECORDS_KEY = "learning.shadow.min_records";
    static final int DEFAULT_SHADOW_MIN_RECORDS = 30;
    static final int SHADOW_MIN_RECORDS_FLOOR = 10;

    private final ExperimentRepository experiments;
    private final ProposalRepository proposals;
    private final ExperimentEvents events;
    private final DecisionStudioService studio;
    private final DecisionDefinitionRepository definitions;
    private final DefinitionCache definitionCache;
    private final DecisionRecordRepository decisionRecords;
    private final ExperimentMetricsRepository metrics;
    private final ExperimentTrafficRouter router;
    private final TenantConfigStore config;

    public ExperimentManager(ExperimentRepository experiments, ProposalRepository proposals, ExperimentEvents events, DecisionStudioService studio,
                             DecisionDefinitionRepository definitions, DefinitionCache definitionCache, DecisionRecordRepository decisionRecords,
                             ExperimentMetricsRepository metrics, ExperimentTrafficRouter router, TenantConfigStore config) {
        this.config = config;
        this.experiments = experiments;
        this.proposals = proposals;
        this.events = events;
        this.studio = studio;
        this.definitions = definitions;
        this.definitionCache = definitionCache;
        this.decisionRecords = decisionRecords;
        this.metrics = metrics;
        this.router = router;
    }

    public Experiment start(String proposalId, ExperimentMode mode, double trafficShare, Map<String, Object> stopRule, String actorRef) {
        if (mode == ExperimentMode.HOLDOUT) return ensureHoldout(trafficShare, actorRef);
        Proposal p = proposals.get(proposalId);
        if (p.status() == ProposalStatus.PROMOTED || p.status() == ProposalStatus.REJECTED || p.status() == ProposalStatus.ROLLED_BACK) {
            throw new ConflictException("Proposal " + proposalId + " is " + p.status() + "; only an open proposal can be put in front of traffic");
        }
        ArtifactRef target = p.changeset().target();
        String targetRef = target.toString();
        experiments.runningOn(targetRef, mode).ifPresent(e -> {
            throw new ConflictException("Experiment " + e.experimentId() + " already runs " + mode + " on " + targetRef + "; only one per artifact per slice (EX-06)");
        });
        Map<String, Object> rule = withTenantFloors(mode, stopRule);
        String decisionName = null;
        Integer candidate = null, baseline = null;
        double share = mode == ExperimentMode.SHADOW ? 1.0 : trafficShare;
        if (mode == ExperimentMode.CANARY && (share <= 0 || share > 0.5)) throw new ValidationException("A canary takes between 0 and 50% of traffic; got " + share);

        if ("decision".equals(target.type())) {
            decisionName = target.name();
            if (!ChangesetApplier.decisionApplicable(p.changeset().changes())) throw new ValidationException("Proposal " + proposalId + " has changes that do not apply to a decision");
            DecisionDefinition live = liveDefinition(decisionName);
            baseline = live.version();
            if (mode == ExperimentMode.CANARY) requirePositiveReplay(p);
            candidate = buildCandidate(decisionName, live, p, mode);
        } else if (mode == ExperimentMode.CANARY) {
            throw new ValidationException("A canary routes decision traffic. " + targetRef + " is not a decision: run it in shadow, then promote it within its max step");
        } else if (!targetRef.startsWith("parameter:live:")) {
            throw new ValidationException("Shadow runs exist for decision changes and learned-parameter live use; " + targetRef + " is evaluated by replay before promotion");
        }

        Instant now = Instant.now();
        Duration duration = rule.get("durationDays") instanceof Number d ? Duration.ofHours(Math.round(d.doubleValue() * 24))
            : mode == ExperimentMode.SHADOW ? DEFAULT_SHADOW_DURATION : DEFAULT_CANARY_DURATION;
        if (mode == ExperimentMode.CANARY) {
            int minDays = ((Number) rule.get("minDurationDays")).intValue();
            if (duration.compareTo(Duration.ofDays(minDays)) < 0) duration = Duration.ofDays(minDays);
        }
        Experiment e = new Experiment(Ids.prefixed("exp"), proposalId, mode, share, ExperimentStatus.RUNNING, now, now.plus(duration), rule, null, now,
            targetRef, decisionName, candidate, baseline, actorRef);
        if (!experiments.insert(e)) {
            if (mode == ExperimentMode.SHADOW && candidate != null) retireVersion(decisionName, candidate);
            throw new ConflictException("Another " + mode + " experiment started on " + targetRef + " at the same time (EX-06)");
        }
        if (mode == ExperimentMode.CANARY) proposals.setStatus(proposalId, ProposalStatus.CANARY, now);
        router.invalidate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", proposalId);
        payload.put("mode", mode.name());
        payload.put("trafficShare", share);
        payload.put("target", targetRef);
        if (candidate != null) payload.put("candidateVersion", candidate);
        if (baseline != null) payload.put("baselineVersion", baseline);
        events.started(e.experimentId(), Actor.parse(actorRef), payload);
        return experiments.get(e.experimentId());
    }

    /** EX-04: the tenant's persistent holdout, kept on each decision's pre-learning baseline version. */
    public Experiment ensureHoldout(double share, String actorRef) {
        return experiments.runningOn(HOLDOUT_TARGET, ExperimentMode.HOLDOUT).orElseGet(() -> {
            Instant now = Instant.now();
            double s = share <= 0 ? DEFAULT_HOLDOUT_SHARE : Math.min(share, 0.2);
            Experiment e = new Experiment(Ids.prefixed("exp"), HOLDOUT_SENTINEL, ExperimentMode.HOLDOUT, s, ExperimentStatus.RUNNING, now, null, Map.of(), null, now,
                HOLDOUT_TARGET, null, null, null, actorRef == null ? "system:experiment-manager" : actorRef);
            if (!experiments.insert(e)) return experiments.runningOn(HOLDOUT_TARGET, ExperimentMode.HOLDOUT).orElseThrow();
            router.invalidate();
            events.started(e.experimentId(), actorRef == null ? Actor.system("experiment-manager") : Actor.parse(actorRef), Map.of("mode", "HOLDOUT", "trafficShare", s));
            return e;
        });
    }

    /**
     * Ends an experiment now with its measured result (shadow verdict or canary sequential test). EX-03: a canary
     * completes only after its minimum duration (its {@code minDurationDays}, never less than the tenant's
     * {@value #CANARY_MIN_DAYS_KEY}), so a lucky first day cannot decide it; stop it, or roll its change back, if it must
     * end sooner.
     */
    public Experiment complete(String experimentId, Actor by) {
        Experiment e = experiments.get(experimentId);
        if (e.status() != ExperimentStatus.RUNNING) throw new ConflictException("Experiment " + experimentId + " is " + e.status());
        if (e.mode() == ExperimentMode.HOLDOUT) throw new ConflictException("The persistent holdout does not complete; stop it explicitly");
        if (e.mode() == ExperimentMode.CANARY && !pastMinimumDuration(e, Instant.now())) {
            throw new ConflictException(String.format(java.util.Locale.ROOT, "This trial has run %.1f of its minimum %d days (EX-03); stop it instead if it has to end now",
                Duration.between(e.startAt(), Instant.now()).toHours() / 24.0, effectiveMinDurationDays(e)));
        }
        Map<String, Object> result = measure(e);
        finish(e, ExperimentStatus.COMPLETED, result, by, true);
        return experiments.get(experimentId);
    }

    /**
     * Ends an experiment without a measured result. D-5: never as COMPLETED, which only {@link #complete} reaches (it
     * measures the experiment and enforces a canary's minimum duration), so a caller cannot hand in its own result.
     */
    public void stop(String experimentId, ExperimentStatus status, Map<String, Object> result, Actor by) {
        Experiment e = experiments.get(experimentId);
        if (e.status() != ExperimentStatus.RUNNING) throw new ConflictException("Experiment " + experimentId + " is " + e.status());
        if (status == ExperimentStatus.COMPLETED) {
            throw new ValidationException("An experiment is completed only through complete, which measures it and enforces a canary's minimum duration (EX-03); stop ends it without a result");
        }
        Map<String, Object> r = new LinkedHashMap<>(result == null ? Map.of() : result);
        r.putIfAbsent("stoppedBy", by.ref());
        finish(e, status == null || status == ExperimentStatus.RUNNING ? ExperimentStatus.STOPPED : status, r, by, true);
    }

    /** Stops a canary because its change is being rolled back; the caller closes the proposal (EX-05 during rollout). */
    public void stopForRollback(String experimentId, String reason, Actor by) {
        Experiment e = experiments.get(experimentId);
        if (e.status() != ExperimentStatus.RUNNING) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stoppedBy", by.ref());
        r.put("reason", reason);
        r.put("rolledBack", true);
        finish(e, ExperimentStatus.STOPPED, r, by, false);
    }

    /** EX-03 minimum duration of a canary as its stop rule records it (default 7 days). */
    static int minDurationDays(Experiment e) { return intRule(e, "minDurationDays", DEFAULT_CANARY_MIN_DAYS); }

    /**
     * D-5: the minimum duration that applies now, never below the tenant's floor, so a canary started before the floor
     * existed (or with {@code minDurationDays: 0}) still runs at least that long.
     */
    int effectiveMinDurationDays(Experiment e) { return Math.max(minDurationDays(e), canaryMinDays()); }

    boolean pastMinimumDuration(Experiment e, Instant now) {
        return !now.isBefore(e.startAt().plus(Duration.ofDays(effectiveMinDurationDays(e))));
    }

    /** The tenant's minimum canary duration ({@value #CANARY_MIN_DAYS_KEY}, default 7), never below 1 day. */
    int canaryMinDays() { return Math.max(CANARY_MIN_DAYS_FLOOR, config.getInt(CANARY_MIN_DAYS_KEY, DEFAULT_CANARY_MIN_DAYS)); }

    /** The tenant's minimum shadow sample ({@value #SHADOW_MIN_RECORDS_KEY}, default 30), never below 10. */
    int shadowMinRecords() { return Math.max(SHADOW_MIN_RECORDS_FLOOR, config.getInt(SHADOW_MIN_RECORDS_KEY, DEFAULT_SHADOW_MIN_RECORDS)); }

    /**
     * D-5 (EX-02, EX-03): the stop rule with the tenant's floors applied. A canary's {@code minDurationDays} and a
     * shadow's {@code minRecords} default to the tenant's minimum and are raised to it when the starter asks for less;
     * each raise is recorded under {@code floorsApplied} so the starter sees why. Negative values are refused.
     */
    Map<String, Object> withTenantFloors(ExperimentMode mode, Map<String, Object> stopRule) {
        Map<String, Object> rule = new LinkedHashMap<>(stopRule == null ? Map.of() : stopRule);
        List<String> raised = new java.util.ArrayList<>();
        if (mode == ExperimentMode.CANARY) {
            int floor = canaryMinDays();
            Object given = rule.get("minDurationDays");
            int days = floor;
            if (given instanceof Number n) {
                if (n.doubleValue() < 0) throw new ValidationException("minDurationDays cannot be negative");
                days = (int) Math.ceil(n.doubleValue());
                if (days < floor) {
                    raised.add(String.format(java.util.Locale.ROOT, "minimum duration raised from %s to %d days, this tenant's minimum (%s)", n, floor, CANARY_MIN_DAYS_KEY));
                    days = floor;
                }
            } else if (given != null) throw new ValidationException("minDurationDays must be a number of days");
            rule.put("minDurationDays", days);
        } else if (mode == ExperimentMode.SHADOW) {
            int floor = shadowMinRecords();
            Object given = rule.get("minRecords");
            int records = floor;
            if (given instanceof Number n) {
                if (n.doubleValue() < 0) throw new ValidationException("minRecords cannot be negative");
                records = (int) Math.ceil(n.doubleValue());
                if (records < floor) {
                    raised.add(String.format(java.util.Locale.ROOT, "minimum sample raised from %s to %d decisions, this tenant's minimum (%s)", n, floor, SHADOW_MIN_RECORDS_KEY));
                    records = floor;
                }
            } else if (given != null) throw new ValidationException("minRecords must be a number of decisions");
            rule.put("minRecords", records);
        }
        if (!raised.isEmpty()) rule.put("floorsApplied", raised);
        return rule;
    }

    private void finish(Experiment e, ExperimentStatus status, Map<String, Object> result, Actor by, boolean reopenProposal) {
        // D4-2: a canary's candidate version is retired on stop too, not only a shadow's — otherwise every canary
        // start leaves a stray `draft` version behind. Before persisting, so the result says whether it happened.
        if ((e.mode() == ExperimentMode.SHADOW || e.mode() == ExperimentMode.CANARY) && e.candidateVersion() != null) {
            result.put("candidateVersionRetired", retireVersion(e.decisionName(), e.candidateVersion()));
        }
        experiments.stop(e.experimentId(), status, result, Instant.now());
        router.invalidate();
        if (status == ExperimentStatus.COMPLETED && e.mode() == ExperimentMode.SHADOW) proposals.mergeReport(e.proposalId(), "shadow", result);
        if (e.mode() == ExperimentMode.CANARY) {
            if (status == ExperimentStatus.COMPLETED) proposals.mergeReport(e.proposalId(), "canary", result);
            if (reopenProposal) reopenAfterCanary(e.proposalId(), result);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", e.proposalId());
        payload.put("mode", e.mode().name());
        payload.put("status", status.name());
        payload.put("result", result);
        if (status == ExperimentStatus.COMPLETED) events.completed(e.experimentId(), by, payload); else events.stopped(e.experimentId(), by, payload);
    }

    /** A canary that ended returns its proposal to where it was: rejected if the candidate was worse, otherwise
     * approved (if a person had approved it) or evaluated, ready for the promotion gate. */
    private void reopenAfterCanary(String proposalId, Map<String, Object> result) {
        Proposal p = proposals.find(proposalId).orElse(null);
        if (p == null || p.status() != ProposalStatus.CANARY) return;
        Object verdict = result.get("msprt") instanceof Map<?, ?> m ? m.get("verdict") : null;
        if (Msprt.Verdict.TREATMENT_WORSE.name().equals(verdict)) proposals.reject(proposalId, "canary: candidate significantly worse than control", Instant.now());
        else proposals.setStatus(proposalId, p.approvedBy() != null ? ProposalStatus.APPROVED : ProposalStatus.EVALUATED, Instant.now());
    }

    // ------------------------------------------------------------------ measurement

    public Map<String, Object> measure(Experiment e) {
        return switch (e.mode()) {
            case SHADOW -> e.decisionName() != null ? shadowResult(e) : learnedValueShadowResult(e);
            case CANARY -> canaryResult(e);
            case HOLDOUT -> Map.of("note", "see GET /api/v1/learning/lift for the holdout comparison");
        };
    }

    Map<String, Object> shadowResult(Experiment e) {
        Map<String, Object> c = metrics.shadowComparison(e.decisionName(), e.candidateVersion(), e.startAt());
        long paired = num(c.get("paired"));
        int minRecords = Math.max(intRule(e, "minRecords", DEFAULT_SHADOW_MIN_RECORDS), shadowMinRecords()); // D-5: never below the tenant floor
        double maxEscalationIncrease = doubleRule(e, "maxEscalationIncrease", 0.05);
        double shadowEsc = paired == 0 ? 0 : num(c.get("shadow_escalations")) / (double) paired;
        double liveEsc = paired == 0 ? 0 : num(c.get("live_escalations")) / (double) paired;
        double shadowP95 = ((Number) c.get("shadow_p95")).doubleValue(), liveP95 = ((Number) c.get("live_p95")).doubleValue();
        long postFailed = num(c.get("post_check_failures"));
        StringBuilder why = new StringBuilder();
        if (paired < minRecords) why.append("only ").append(paired).append(" paired decisions (need ").append(minRecords).append("); ");
        if (shadowEsc > liveEsc + maxEscalationIncrease) why.append(String.format(java.util.Locale.ROOT, "escalations would rise from %.1f%% to %.1f%%; ", liveEsc * 100, shadowEsc * 100));
        if (postFailed > 0) why.append(postFailed).append(" answers failed post-checks; ");
        if (shadowP95 > Math.max(300, liveP95 * 1.25)) why.append(String.format(java.util.Locale.ROOT, "p95 latency %.0f ms vs %.0f ms live; ", shadowP95, liveP95));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("passed", why.isEmpty());
        r.put("reason", why.isEmpty() ? "shadow matched live within guardrails" : why.toString().trim());
        r.put("shadowVersion", e.candidateVersion());
        r.put("baselineVersion", e.baselineVersion());
        r.put("shadowRecords", num(c.get("shadow_records")));
        r.put("paired", paired);
        r.put("agreementRate", paired == 0 ? 0 : round(num(c.get("agreed")) / (double) paired));
        r.put("shadowEscalationRate", round(shadowEsc));
        r.put("liveEscalationRate", round(liveEsc));
        r.put("postCheckFailures", postFailed);
        r.put("shadowLatencyP95Ms", round(shadowP95));
        r.put("liveLatencyP95Ms", round(liveP95));
        r.put("measuredAt", Instant.now().toString());
        return r;
    }

    Map<String, Object> learnedValueShadowResult(Experiment e) {
        Map<String, Object> c = metrics.learnedValueShadow(e.startAt());
        long affected = num(c.get("decisions_affected"));
        int minRecords = Math.max(intRule(e, "minRecords", DEFAULT_SHADOW_MIN_RECORDS), shadowMinRecords()); // D-5: never below the tenant floor
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("passed", affected >= minRecords);
        r.put("reason", affected >= minRecords ? "learned values were available for enough live decisions" : "only " + affected + " live decisions had a learned value to compare (need " + minRecords + ")");
        r.put("decisionsAffected", affected);
        r.put("factsHeldBack", num(c.get("facts_held_back")));
        r.put("meanShift", round(((Number) c.get("mean_shift")).doubleValue()));
        r.put("measuredAt", Instant.now().toString());
        return r;
    }

    Map<String, Object> canaryResult(Experiment e) {
        List<Double> treatment = metrics.armRewards(e.decisionName(), e.experimentId(), "canary", e.startAt());
        List<Double> control = metrics.controlRewards(e.decisionName(), e.baselineVersion(), e.startAt());
        Msprt.Result t = Msprt.test(treatment, control, doubleRule(e, "alpha", 0.05), intRule(e, "minPerArm", 30),
            e.stopRule().get("tau2") instanceof Number n ? n.doubleValue() : null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("candidateVersion", e.candidateVersion());
        r.put("baselineVersion", e.baselineVersion());
        r.put("trafficShare", e.trafficShare());
        r.put("msprt", t.asMap());
        r.put("liftEstimate", round(t.difference()));
        r.put("measuredAt", Instant.now().toString());
        return r;
    }

    // ------------------------------------------------------------------ sweep

    /** Completes shadows at their end time; checks every canary's sequential test and stops it once it is decided. */
    @Scheduled(fixedDelayString = "${oms.learning.experiment-sweep-ms:1800000}", initialDelayString = "${oms.learning.experiment-sweep-ms:1800000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException ex) { log.error("Experiment sweep failed: {}", ex.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(30))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        int n = 0;
        Instant now = Instant.now();
        Actor sys = Actor.system("experiment-manager");
        for (Experiment e : experiments.running()) {
            try {
                if (e.mode() == ExperimentMode.SHADOW && e.endAt() != null && now.isAfter(e.endAt())) { complete(e.experimentId(), sys); n++; }
                else if (e.mode() == ExperimentMode.CANARY) {
                    Map<String, Object> r = canaryResult(e);
                    experiments.updateResult(e.experimentId(), r);
                    Object verdict = ((Map<?, ?>) r.get("msprt")).get("verdict");
                    boolean decided = Msprt.Verdict.TREATMENT_BETTER.name().equals(verdict) || Msprt.Verdict.TREATMENT_WORSE.name().equals(verdict);
                    // EX-03: the sequential test may stop a canary early, but never before its minimum duration.
                    boolean earlyStopAllowed = pastMinimumDuration(e, now);
                    boolean expired = now.isAfter(e.startAt().plus(MAX_CANARY_DURATION)) || (e.endAt() != null && now.isAfter(e.endAt()) && !Msprt.Verdict.INSUFFICIENT_DATA.name().equals(verdict));
                    if ((decided && earlyStopAllowed) || expired) { finish(e, ExperimentStatus.COMPLETED, r, sys, true); n++; }
                }
            } catch (RuntimeException ex) { log.warn("Experiment {} sweep step failed: {}", e.experimentId(), ex.toString()); }
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers

    private int buildCandidate(String decisionName, DecisionDefinition live, Proposal p, ExperimentMode mode) {
        String yaml = ChangesetApplier.applyToDecision(live.yaml(), p.changeset().changes());
        return SystemPrincipal.call(() -> {
            DecisionDefinition draft = studio.createDraft(decisionName, yaml);
            var lint = studio.lint(decisionName, draft.version());
            if (!lint.ok()) throw new ConflictException("The candidate version of " + decisionName + " does not lint: " + lint.issues());
            if (mode == ExperimentMode.CANARY) {
                EvaluationReport gate = studio.evaluate(decisionName, draft.version());
                if (!gate.passed()) throw new ConflictException("The candidate version of " + decisionName + " fails its golden set (GR-11): " + gate.gateFailures());
            } else {
                studio.publish(decisionName, draft.version(), "shadow", true);
            }
            return draft.version();
        });
    }

    private static void requirePositiveReplay(Proposal p) {
        Object ci = p.evaluationReport() == null ? null : p.evaluationReport().get("confidenceInterval");
        Object lift = p.evaluationReport() == null ? null : p.evaluationReport().get("liftEstimate");
        boolean positive = ci instanceof Map<?, ?> m && m.get("lower") instanceof Number lower ? lower.doubleValue() > 0 : lift instanceof Number l && l.doubleValue() > 0;
        if (!positive && p.approvedBy() == null) {
            throw new ConflictException("A canary exposes real customers: run replay evaluation first (EX-01) and get a positive lift, or have a person approve the proposal");
        }
    }

    /** Retires the version if it is still a shadow or draft candidate (a canary's own candidate never leaves "draft"
     * while it runs, D4-2); returns whether it actually did, so the caller's result can say so. */
    private boolean retireVersion(String decisionName, int version) {
        try {
            return definitions.find(decisionName, version).map(row -> {
                if (!"shadow".equals(row.definition().status()) && !"draft".equals(row.definition().status())) return false;
                definitions.setStatus(decisionName, version, "retired", null);
                definitionCache.invalidate(decisionName);
                return true;
            }).orElse(false);
        } catch (RuntimeException ex) {
            log.warn("Could not retire {} version {}: {}", decisionName, version, ex.toString());
            return false;
        }
    }

    private DecisionDefinition liveDefinition(String decisionName) {
        return definitions.byStatus(decisionName, "live").stream().findFirst()
            .orElseThrow(() -> new ConflictException("Decision " + decisionName + " has no live version")).definition();
    }

    /** EX-03: sticky assignment; the same key always lands in the same arm for the life of one experiment. */
    public static boolean stickyInTreatment(String experimentId, String key, double trafficShare) {
        if (key == null || key.isBlank() || trafficShare <= 0) return false;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((experimentId + ":" + key).getBytes(StandardCharsets.UTF_8));
            long bucket = ((d[0] & 0xFF) << 8 | (d[1] & 0xFF)) % 10_000L;
            return bucket < Math.round(trafficShare * 10_000);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public List<Experiment> running() { return experiments.running(); }
    public List<Experiment> recent(int limit) { return experiments.recent(limit); }
    public Experiment get(String id) { return experiments.get(id); }

    private static int intRule(Experiment e, String k, int d) { return e.stopRule() != null && e.stopRule().get(k) instanceof Number n ? n.intValue() : d; }
    private static double doubleRule(Experiment e, String k, double d) { return e.stopRule() != null && e.stopRule().get(k) instanceof Number n ? n.doubleValue() : d; }
    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private static double round(double v) { return Math.round(v * 10_000d) / 10_000d; }
}
