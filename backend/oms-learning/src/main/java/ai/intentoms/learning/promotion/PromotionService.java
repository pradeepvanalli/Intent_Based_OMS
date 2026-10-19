package ai.intentoms.learning.promotion;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.governance.LiveParameterRepository;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.learning.store.LearningBaselineRepository;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EX-07: applies a proposal once {@link PromotionGuard} says yes, for every change type, and rolls it back (AC4).
 *
 * <ul>
 *   <li>{@code decision:<name>} (ThresholdChange, WeightChange, RuleAdd, RuleRetire, decision-field ParameterChange):
 *       the live YAML plus the changeset ({@link ChangesetApplier}) becomes a new version that must lint clean and pass
 *       its golden set (GR-11) before it is published live. The version it replaced is remembered for rollback, and the
 *       first one ever replaced becomes the decision's holdout baseline (EX-04).</li>
 *   <li>{@code config:<key>} (WeightChange on a weight map such as {@code sourcing.weights}, AllowListChange,
 *       ParameterChange): the tenant config value is changed; the previous value is remembered.</li>
 *   <li>{@code parameter:<subjectType>:<subjectId>:<factKey>}: a new learned-fact version;
 *       {@code parameter:live:<family>}: live use of a learned parameter family is granted or revoked.</li>
 * </ul>
 * Studio steps run as the system principal: the decision studio only refuses model and agent principals (GR-14), and the
 * human or EX-07 approval has already happened by the time this runs. An auto-promotion is published as an emergency
 * publish, so the studio schedules its usual 48-hour retro review.
 */
@Service
public class PromotionService {
    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);
    /** Writes the {@code type} id, which serialising a TypedValue by its runtime class would drop. */
    private static final com.fasterxml.jackson.core.type.TypeReference<TypedValue> TYPED_VALUE = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private final ProposalRepository proposals;
    private final LearnerRepository learners;
    private final ObjectiveProfileRepository objectives;
    private final LearnedFactRepository facts;
    private final DecisionStudioService decisionStudio;
    private final DecisionDefinitionRepository definitions;
    private final LearningEvents events;
    private final ai.intentoms.decision.record.DecisionRecordRepository decisionRecords;
    private final ParameterResolver parameters;
    private final LiveParameterRepository liveParameters;
    private final LearnerRegistry registry;
    private final TenantConfigStore config;
    private final LearningBaselineRepository baselines;
    private final ai.intentoms.learning.governance.LearnerGovernance governance;
    private final ai.intentoms.learning.store.HypothesisRepository hypotheses;
    private final ai.intentoms.learning.experiment.ExperimentManager experiments;
    private final List<ConfigDefaults> configDefaults;
    private final ProposalOwnership ownership;

    /** Promotions of one target per week when no learner owns the proposal's target, GV-02. */
    public static final String TARGET_BUDGET_KEY = "learning.max_promotions_per_target_per_week";

    public PromotionService(ProposalRepository proposals, LearnerRepository learners, ObjectiveProfileRepository objectives, LearnedFactRepository facts,
                             DecisionStudioService decisionStudio, DecisionDefinitionRepository definitions, LearningEvents events,
                             ai.intentoms.decision.record.DecisionRecordRepository decisionRecords, ParameterResolver parameters, LiveParameterRepository liveParameters,
                             LearnerRegistry registry, TenantConfigStore config, LearningBaselineRepository baselines,
                             ai.intentoms.learning.governance.LearnerGovernance governance, ai.intentoms.learning.store.HypothesisRepository hypotheses,
                             ai.intentoms.learning.experiment.ExperimentManager experiments, List<ConfigDefaults> configDefaults, ProposalOwnership ownership) {
        this.ownership = ownership;
        this.governance = governance;
        this.hypotheses = hypotheses;
        this.experiments = experiments;
        this.configDefaults = configDefaults;
        this.proposals = proposals;
        this.learners = learners;
        this.objectives = objectives;
        this.facts = facts;
        this.decisionStudio = decisionStudio;
        this.definitions = definitions;
        this.events = events;
        this.decisionRecords = decisionRecords;
        this.parameters = parameters;
        this.liveParameters = liveParameters;
        this.registry = registry;
        this.config = config;
        this.baselines = baselines;
    }

    /** Scheduled auto-promotion sweep (the autonomous half of EX-07); approvals go through {@code POST /proposals/{id}/approve}. */
    @Scheduled(fixedDelayString = "${oms.learning.auto-promote-sweep-ms:1800000}", initialDelayString = "${oms.learning.auto-promote-sweep-ms:1800000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Auto-promotion sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(30))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        // GV-03: while the global or tenant kill switch is on, nothing is promoted automatically.
        if (!governance.learningOn()) return 0;
        int n = 0;
        for (Proposal p : proposals.byStatus("EVALUATED", 200)) {
            try { if (tryAutoPromote(p)) n++; }
            catch (RuntimeException e) { log.warn("Auto-promotion of {} failed, leaving it for a person: {}", p.proposalId(), e.toString()); }
        }
        return n;
    }

    private boolean tryAutoPromote(Proposal p) {
        PromotionGuard.Context ctx = context(p);
        PromotionGuard.Result r = PromotionGuard.evaluate(p, ctx);
        if (r.verdict() != PromotionGuard.Verdict.AUTO_PROMOTE) return false;
        apply(p, ctx.learner(), r.reason(), Actor.system("promotion-service"), null);
        return true;
    }

    /** Manual promotion of an already-APPROVED proposal with no override (see {@link #promote(String, Actor, String)}). */
    public Proposal promote(String proposalId, Actor by) { return promote(proposalId, by, null); }

    /**
     * A person promotes an APPROVED proposal (EX-07). When its evidence or budget falls short the call is refused with
     * code {@code promotion_needs_override} and the list of what would be waived; repeating it with a written
     * {@code overrideReason} promotes it and records the waiver on the ledger and the proposal.
     */
    public Proposal promote(String proposalId, Actor by, String overrideReason) {
        Proposal p = proposals.get(proposalId);
        ai.intentoms.learning.model.ProposalLifecycle.require(p, ProposalStatus.PROMOTED, "promote");
        PromotionGuard.Context ctx = context(p);
        PromotionGuard.Result r = PromotionGuard.evaluateManualPromote(p, ctx, overrideReason);
        if (r.verdict() == PromotionGuard.Verdict.NEEDS_OVERRIDE) {
            throw new ai.intentoms.platform.error.OmsException(org.springframework.http.HttpStatus.CONFLICT, "promotion_needs_override", r.reason(),
                Map.of("proposalId", proposalId, "waivable", r.waivable()), null);
        }
        if (r.verdict() != PromotionGuard.Verdict.AUTO_PROMOTE) throw new ConflictException("Cannot promote: " + r.reason());
        Map<String, Object> override = null;
        if (!r.waivable().isEmpty()) {
            override = new LinkedHashMap<>();
            override.put("by", by.ref());
            override.put("reason", overrideReason.trim());
            override.put("waived", r.waivable());
            override.put("at", Instant.now().toString());
        }
        apply(p, ctx.learner(), r.reason(), by, override);
        return proposals.get(proposalId);
    }

    /** What a person would have to waive to promote this proposal now; empty when nothing (change card, UI). */
    public List<String> promotionConcerns(Proposal p) { return PromotionGuard.concerns(p, context(p)); }

    /** Why this proposal cannot be promoted at all right now (freeze window, kill switch, paused learner…), or null. */
    public String promotionBlocker(Proposal p) {
        PromotionGuard.Result r = PromotionGuard.evaluateManualPromote(p, context(p), "dry run: check hard stops only");
        return r.verdict() == PromotionGuard.Verdict.BLOCKED ? r.reason() : null;
    }

    private PromotionGuard.Context context(Proposal p) {
        LearnerState learner = learnerFor(p);
        List<FreezeWindows.Window> freeze = FreezeWindows.load(objectives.live().orElse(null));
        String target = p.changeset().target().toString();
        int promotedThisWeek = proposals.promotionsOfTargetSince(target, Instant.now().minus(Duration.ofDays(7)));
        int budget = registry.descriptor(learner.learnerKey()).isPresent() ? learner.maxPromotionsPerWeek() : Math.max(0, config.getInt(TARGET_BUDGET_KEY, 1));
        return new PromotionGuard.Context(learner, FreezeWindows.toGuardWindows(freeze), Instant.now(), governance.offReason(), promotedThisWeek, budget);
    }

    private void apply(Proposal p, LearnerState learner, String reason, Actor by, Map<String, Object> override) {
        ArtifactRef target = p.changeset().target();
        Map<String, Object> snapshot = switch (target.type()) {
            case "decision" -> applyToDecision(p, target.name());
            case "config" -> applyToConfig(p, target.name());
            case "parameter" -> applyToParameter(p, target.name());
            default -> throw new ValidationException("Unknown artifact type '" + target.type() + "' on proposal " + p.proposalId());
        };
        Instant now = Instant.now();
        proposals.setRollbackSnapshot(p.proposalId(), snapshot);
        proposals.promote(p.proposalId(), now);
        if (override != null) proposals.setPromotionNote(p.proposalId(), override);
        if (registry.descriptor(learner.learnerKey()).isPresent()) learners.recordRun(learner.learnerKey(), now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.toString());
        payload.put("tier", p.tier().name());
        payload.put("reason", reason);
        payload.put("learner", learner.learnerKey());
        payload.put("changeTypes", p.changeset().changes().stream().map(c -> c.getClass().getSimpleName()).toList());
        payload.put("applied", snapshot);
        if (override != null) payload.put("override", override);
        events.proposalPromoted(p.proposalId(), by, payload);
    }

    // ------------------------------------------------------------------ decision targets

    private Map<String, Object> applyToDecision(Proposal p, String decisionName) {
        List<PolicyChange> changes = p.changeset().changes();
        if (!ChangesetApplier.decisionApplicable(changes)) {
            throw new ConflictException("Proposal " + p.proposalId() + " mixes changes that do not apply to decision " + decisionName);
        }
        DecisionDefinition live = liveDefinition(decisionName);
        String candidateYaml = ChangesetApplier.applyToDecision(live.yaml(), changes);
        return SystemPrincipal.call(() -> {
            DecisionDefinition draft = decisionStudio.createDraft(decisionName, candidateYaml);
            var lint = decisionStudio.lint(decisionName, draft.version());
            if (!lint.ok()) throw new ConflictException("The promoted version of " + decisionName + " does not lint: " + lint.issues());
            EvaluationReport gate = decisionStudio.evaluate(decisionName, draft.version());
            if (!gate.passed()) throw new ConflictException("The promoted version of " + decisionName + " fails its golden set (GR-11): " + gate.gateFailures());
            decisionStudio.publish(decisionName, draft.version(), "live", true);
            baselines.recordIfAbsent(decisionName, live.version(), p.proposalId());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("kind", "decision");
            s.put("decision", decisionName);
            s.put("previousVersion", live.version());
            s.put("newVersion", draft.version());
            return s;
        });
    }

    private DecisionDefinition liveDefinition(String decisionName) {
        return definitions.byStatus(decisionName, "live").stream().findFirst()
            .orElseThrow(() -> new ConflictException("Decision " + decisionName + " has no live version to base a promotion on")).definition();
    }

    // ------------------------------------------------------------------ config targets

    private Map<String, Object> applyToConfig(Proposal p, String key) {
        Object before = config.get(key).orElse(null);
        Object defaults = configDefaults.stream().map(d -> d.defaultFor(key)).flatMap(java.util.Optional::stream).findFirst().orElse(null);
        Object value = ChangesetApplier.applyAllToConfig(key, before, p.changeset().changes(), defaults);
        config.set(key, configValue(value));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "config");
        s.put("key", key);
        s.put("existed", before != null);
        s.put("previous", before);
        s.put("value", value);
        return s;
    }

    // ------------------------------------------------------------------ parameter targets

    private Map<String, Object> applyToParameter(Proposal p, String parameterRef) {
        if (parameterRef.startsWith("live:")) return applyLiveUse(p, parameterRef.substring("live:".length()));
        // "<subjectType>:<subjectId>:<factKey>" names the learned fact a ParameterChange moves (e.g. "NODE:node_12:SAFETY_STOCK_UNITS").
        String[] parts = parameterRef.split(":", 3);
        if (parts.length != 3 || !(p.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc)) {
            throw new ConflictException("A parameter promotion needs a ParameterChange against 'subjectType:subjectId:factKey' or 'live:<family>'; got '" + parameterRef + "'");
        }
        SubjectType st;
        FactKey key;
        try { st = SubjectType.valueOf(parts[0]); key = FactKey.valueOf(parts[2]); }
        catch (IllegalArgumentException e) { throw new ConflictException("Unrecognised subjectType/factKey in parameter ref '" + parameterRef + "'"); }
        Instant now = Instant.now();
        var current = facts.active(st, parts[1], key);
        int nextVersion = current.map(f -> f.version() + 1).orElse(1);
        TypedValue value = key.shape() == TypedValue.NumberValue.class
            ? new TypedValue.NumberValue(pc.to() instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(pc.to())))
            : current.map(LearnedFact::value).orElseThrow(() -> new ConflictException("No active fact to base a non-numeric ParameterChange on"));
        facts.supersede(st, parts[1], key, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), st, parts[1], key, value, 1.0, 0, "promotion-service", nextVersion, now, null, now));
        events.factLearned(parts[1], Actor.system("promotion-service"), Map.of("subjectType", st.name(), "factKey", key.name(), "version", nextVersion, "proposalId", p.proposalId()));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "fact");
        s.put("subjectType", st.name());
        s.put("subjectId", parts[1]);
        s.put("factKey", key.name());
        s.put("previous", current.map(f -> Json.readMap(Json.write(f.value(), TYPED_VALUE))).orElse(null));
        s.put("previousVersion", current.map(LearnedFact::version).orElse(0));
        s.put("newVersion", nextVersion);
        return s;
    }

    /**
     * Live use of a learned parameter family ({@code parameter:live:<family>}): the change's {@code to} is
     * {@code {"enabled": true, "min": .., "max": .., "maxStep": .., "learner": ..}}; {@code enabled: false} revokes.
     */
    private Map<String, Object> applyLiveUse(Proposal p, String family) {
        if (!(p.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc) || !(pc.to() instanceof Map<?, ?> to)) {
            throw new ConflictException("A live-use change needs a ParameterChange whose 'to' is {enabled, min, max, maxStep}");
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "live");
        s.put("family", family);
        s.put("previous", liveParameters.find(family).map(lp -> Json.convert(lp, Map.class)).orElse(null));
        String by = "proposal:" + p.proposalId();
        if (Boolean.FALSE.equals(to.get("enabled"))) parameters.revoke(family, by);
        else {
            String learner = to.get("learner") != null ? String.valueOf(to.get("learner")) : governingLearner(p);
            parameters.promote(family, learner, num(to.get("min")), num(to.get("max")), num(to.get("maxStep")), p.proposalId(), by);
        }
        return s;
    }

    // ------------------------------------------------------------------ rollback (EX-05, AC4)

    /**
     * Reverts a promoted proposal to exactly what it replaced: the previous decision version, the previous config value
     * (or no value), the previous fact value, or the previous live-use state. Refuses when a later change to the same
     * decision is live, since rolling back underneath it would silently discard that change too.
     */
    @SuppressWarnings("unchecked")
    public Proposal rollback(String proposalId, Actor by, String reason) {
        Proposal p = proposals.get(proposalId);
        if (p.status() == ProposalStatus.CANARY) return rollbackCanary(p, by, reason);
        if (p.status() != ProposalStatus.PROMOTED) throw ai.intentoms.learning.model.ProposalLifecycle.refused(proposalId, p.status(), "roll back");
        Map<String, Object> s = proposals.rollbackSnapshot(proposalId)
            .orElseThrow(() -> new ConflictException("Proposal " + proposalId + " was promoted before rollback snapshots were recorded; roll it back in the Decision Studio"));
        String kind = String.valueOf(s.get("kind"));
        Map<String, Object> restored = new LinkedHashMap<>();
        restored.put("kind", kind);
        switch (kind) {
            case "decision" -> {
                String name = String.valueOf(s.get("decision"));
                int newVersion = ((Number) s.get("newVersion")).intValue();
                int previous = ((Number) s.get("previousVersion")).intValue();
                int liveNow = liveDefinition(name).version();
                if (liveNow != newVersion) throw new ConflictException("Decision " + name + " is now on version " + liveNow + ", not " + newVersion + " that this proposal published; roll back the later change first");
                // The studio's one-click rollback revives the most recently retired version. If something else was retired
                // since (a finished shadow, say), republish the replaced version's YAML as a new version instead.
                boolean lastRetiredIsPrevious = definitions.lastRetired(name).map(r -> r.definition().version() == previous).orElse(false);
                int liveVersion = SystemPrincipal.call(() -> {
                    if (lastRetiredIsPrevious) return decisionStudio.rollback(name).definition().version();
                    String yaml = definitions.find(name, previous).orElseThrow(() -> new ConflictException("Version " + previous + " of " + name + " no longer exists")).definition().yaml();
                    DecisionDefinition copy = decisionStudio.createDraft(name, yaml);
                    decisionStudio.publish(name, copy.version(), "live", true);
                    return copy.version();
                });
                restored.put("decision", name);
                restored.put("liveVersion", liveVersion);
                restored.put("restoredFromVersion", previous);
            }
            case "config" -> {
                String key = String.valueOf(s.get("key"));
                if (Boolean.TRUE.equals(s.get("existed"))) config.set(key, configValue(s.get("previous"))); else config.delete(key);
                restored.put("key", key);
                restored.put("value", s.get("previous"));
            }
            case "fact" -> {
                SubjectType st = SubjectType.valueOf(String.valueOf(s.get("subjectType")));
                String subjectId = String.valueOf(s.get("subjectId"));
                FactKey key = FactKey.valueOf(String.valueOf(s.get("factKey")));
                Instant now = Instant.now();
                int next = facts.active(st, subjectId, key).map(f -> f.version() + 1).orElse(1);
                facts.supersede(st, subjectId, key, now);
                if (s.get("previous") instanceof Map<?, ?> prevValue) {
                    TypedValue v = Json.convert(prevValue, TypedValue.class);
                    facts.insert(new LearnedFact(Ids.prefixed("fact"), st, subjectId, key, v, 1.0, 0, "promotion-rollback", next, now, null, now));
                    restored.put("value", prevValue);
                }
                restored.put("fact", st + ":" + subjectId + ":" + key);
            }
            case "live" -> {
                String family = String.valueOf(s.get("family"));
                String rb = "rollback:" + proposalId;
                if (s.get("previous") instanceof Map<?, ?> prev && "LIVE".equals(String.valueOf(prev.get("status")))) {
                    Map<String, Object> pm = (Map<String, Object>) prev;
                    parameters.promote(family, String.valueOf(pm.get("learnerKey")), num(pm.get("min")), num(pm.get("max")), num(pm.get("maxStep")),
                        pm.get("proposalId") == null ? null : String.valueOf(pm.get("proposalId")), rb);
                    restored.put("live", true);
                } else {
                    parameters.revoke(family, rb);
                    restored.put("live", false);
                }
                restored.put("family", family);
            }
            default -> throw new ConflictException("Unknown rollback snapshot kind '" + kind + "'");
        }
        String why = reason == null || reason.isBlank() ? "rolled back by " + by.ref() : reason;
        proposals.rollback(proposalId, why, Instant.now());
        events.proposalRolledBack(proposalId, by, Map.of("target", p.changeset().target().toString(), "reason", why, "restored", restored));
        counterEvidence(p, "Rolled back " + Instant.now().toString().substring(0, 10) + ": " + why, by);
        return proposals.get(proposalId);
    }

    /**
     * EX-05 "during rollout": a change being tried on a slice of customers is rolled back by stopping its canary (the
     * slice goes back to the live version at once; the candidate was never published) and closing the proposal.
     */
    public Proposal rollbackCanary(Proposal p, Actor by, String reason) {
        String why = reason == null || reason.isBlank() ? "trial stopped by " + by.ref() : reason;
        for (var e : experiments.running()) {
            if (p.proposalId().equals(e.proposalId()) && e.mode() == ai.intentoms.learning.model.ExperimentMode.CANARY) {
                experiments.stopForRollback(e.experimentId(), why, by);
            }
        }
        proposals.rollback(p.proposalId(), why, Instant.now());
        events.proposalRolledBack(p.proposalId(), by, Map.of("target", p.changeset().target().toString(), "reason", why, "restored", Map.of("kind", "canary", "live", "unchanged")));
        counterEvidence(p, "Trial rolled back " + Instant.now().toString().substring(0, 10) + ": " + why, by);
        return proposals.get(p.proposalId());
    }

    /**
     * Defect 27: switching a learned family off through {@code /live-parameters/{family}/revoke} also closes the proposal
     * that switched it on, so its card and history stop saying it is live.
     */
    public void liveUseRevoked(String family, Actor by) {
        liveParameters.find(family).map(LiveParameterRepository.LiveParameter::proposalId).flatMap(proposals::find).ifPresent(p -> {
            if (p.status() != ProposalStatus.PROMOTED) return;
            String why = "live use of " + family + " switched off by " + by.ref();
            // N4: a precautionary switch-off, not evidence against the change; people may propose it again at once.
            proposals.rollback(p.proposalId(), why, Instant.now(), false);
            events.proposalRolledBack(p.proposalId(), by, Map.of("target", p.changeset().target().toString(), "reason", why, "restored", Map.of("kind", "live", "family", family, "live", false)));
        });
    }

    /** §5: a rolled-back or rejected change goes back to its hypothesis as evidence against it. */
    public void counterEvidence(Proposal p, String note, Actor by) {
        if (p.hypothesisId() == null) return;
        try {
            hypotheses.addCounterEvidence(p.hypothesisId(), note);
            events.hypothesisCounterEvidence(p.hypothesisId(), by, Map.of("proposalId", p.proposalId(), "note", note));
        } catch (RuntimeException e) { log.warn("Could not record counter-evidence on hypothesis {}: {}", p.hypothesisId(), e.toString()); }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * N5: the state of the learner that owns the proposal's target ({@link ProposalOwnership}), whoever drafted it, so
     * that learner's autonomy, kill switch, pause and budget apply. Only a change no learner owns gets the tier-only
     * default (L1 and the tenant's per-target budget).
     */
    private LearnerState learnerFor(Proposal p) {
        var owner = ownership.owner(p);
        if (owner.isPresent()) return learners.getOrCreate(owner.get());
        return new LearnerState(learnerKeyOf(p), AutonomyLevel.L1_RECOMMEND, true, Integer.MAX_VALUE, null, null, "system", Instant.now(), null);
    }

    /** The key of the learner governing this proposal, or the bare author when no learner owns its target. */
    public String governingLearner(Proposal p) { return ownership.owner(p).orElse(learnerKeyOf(p)); }

    /** The author without its {@code system:} or {@code system:demo-seed.} prefix (a learner key when a learner drafted it). */
    static String learnerKeyOf(Proposal p) {
        String by = p.createdBy() == null ? "" : p.createdBy();
        String learner = ProposalOwnership.authorLearner(by);
        return learner != null ? learner : by;
    }

    /** Tenant config stores JSON; a plain string must be written as a JSON string, not as raw JSON text. */
    private static Object configValue(Object v) { return v instanceof String str ? Json.write(str) : v; }

    private static Double num(Object o) { return o instanceof Number n ? n.doubleValue() : o == null ? null : Double.valueOf(String.valueOf(o)); }

    /** GV-05: tenant-defined freeze windows live in {@code objective_profile.constraints.freezeWindows}, each
     * {@code {"from": "yyyy-MM-dd", "to": "yyyy-MM-dd"}}. */
    static final class FreezeWindows {
        record Window(java.time.LocalDate from, java.time.LocalDate to) {}

        static List<Window> load(ObjectiveProfile profile) {
            if (profile == null || profile.constraints() == null) return List.of();
            Object raw = profile.constraints().get("freezeWindows");
            if (!(raw instanceof List<?> list)) return List.of();
            List<Window> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    try { out.add(new Window(java.time.LocalDate.parse(String.valueOf(m.get("from"))), java.time.LocalDate.parse(String.valueOf(m.get("to"))))); }
                    catch (RuntimeException ignored) { /* malformed window entry, skip rather than fail the whole sweep */ }
                }
            }
            return out;
        }

        static List<PromotionGuard.FreezeWindow> toGuardWindows(List<Window> windows) {
            return windows.stream().map(w -> new PromotionGuard.FreezeWindow(w.from(), w.to())).toList();
        }
    }
}
