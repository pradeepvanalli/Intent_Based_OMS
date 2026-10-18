package ai.intentoms.learning.promotion;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.event.LearningEvents;
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
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EX-07: the promotion gate by tier, driving the real underlying change once {@link PromotionGuard} says yes.
 * Automated promotion runs as the system principal (same pattern as {@code DecisionEscalationTaskSubscriber}) —
 * {@code DecisionStudioService.publish()}'s {@code requireHuman()} check only excludes DECISION/AGENT principal
 * types, so a SYSTEM actor can legitimately publish on a learner's behalf while still keeping GR-14's actual rule
 * (no model or agent publishes a decision) intact.
 *
 * <p><b>Scope, stated honestly</b>: automated application against a live decision is wired for {@code
 * ThresholdChange} only (a direct structured edit, {@code bands.<band> = to}) and for {@code ParameterChange}
 * against a {@code parameter:} target (a new superseding {@link LearnedFact} version). {@code WeightChange},
 * {@code RuleAdd}, {@code RuleRetire} and {@code AllowListChange} against a {@code decision:} target are NOT
 * auto-applied — {@link #promote} refuses with a clear reason rather than marking a proposal PROMOTED without
 * actually changing anything, since a decision whose rules differ from what the system claims was promoted is far
 * worse than one that waits for a human to apply it through the Decision Studio.
 */
@Service
public class PromotionService {
    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final ProposalRepository proposals;
    private final LearnerRepository learners;
    private final ObjectiveProfileRepository objectives;
    private final LearnedFactRepository facts;
    private final DecisionStudioService decisionStudio;
    private final LearningEvents events;
    private final ai.intentoms.decision.record.DecisionRecordRepository decisionRecords;
    private final ai.intentoms.learning.governance.ParameterResolver parameters;
    private final ai.intentoms.learning.governance.LearnerRegistry registry;

    @Value("${oms.learning.auto-promote-sweep-ms:1800000}") private long sweepMs;

    public PromotionService(ProposalRepository proposals, LearnerRepository learners, ObjectiveProfileRepository objectives, LearnedFactRepository facts,
                             DecisionStudioService decisionStudio, LearningEvents events, ai.intentoms.decision.record.DecisionRecordRepository decisionRecords,
                             ai.intentoms.learning.governance.ParameterResolver parameters, ai.intentoms.learning.governance.LearnerRegistry registry) {
        this.proposals = proposals;
        this.learners = learners;
        this.objectives = objectives;
        this.facts = facts;
        this.decisionStudio = decisionStudio;
        this.events = events;
        this.decisionRecords = decisionRecords;
        this.parameters = parameters;
        this.registry = registry;
    }

    /** Scheduled auto-promotion sweep (the "T0 auto / T1 at L3" half of EX-07); approvals still go through the
     * existing manual {@code POST /proposals/{id}/approve} in {@code LearningController}. */
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
        int n = 0;
        List<FreezeWindows.Window> freeze = FreezeWindows.load(objectives.live().orElse(null));
        for (Proposal p : proposals.byStatus("EVALUATED", 200)) {
            if (tryAutoPromote(p, freeze)) n++;
        }
        return n;
    }

    private boolean tryAutoPromote(Proposal p, List<FreezeWindows.Window> freeze) {
        String learnerKey = learnerKeyOf(p);
        LearnerState learner = learnerFor(p);
        int promotedThisWeek = countPromotedInTrailingWeek(learnerKey);
        PromotionGuard.Result r = PromotionGuard.evaluate(p, learner, FreezeWindows.toGuardWindows(freeze), Instant.now(), promotedThisWeek);
        if (r.verdict() != PromotionGuard.Verdict.AUTO_PROMOTE) return false;
        return runAsSystem(() -> { apply(p, learner, r.reason()); return true; });
    }

    /** Manual promotion of an already-APPROVED proposal (called from a human-facing controller). */
    public Proposal promote(String proposalId, Actor by) {
        Proposal p = proposals.get(proposalId);
        LearnerState learner = learnerFor(p);
        List<FreezeWindows.Window> freeze = FreezeWindows.load(objectives.live().orElse(null));
        PromotionGuard.Result r = PromotionGuard.evaluateManualPromote(p, learner, FreezeWindows.toGuardWindows(freeze), Instant.now());
        if (r.verdict() != PromotionGuard.Verdict.AUTO_PROMOTE) throw new ConflictException("Cannot promote: " + r.reason());
        apply(p, learner, r.reason());
        return proposals.get(proposalId);
    }

    private void apply(Proposal p, LearnerState learner, String reason) {
        ArtifactRef target = p.changeset().target();
        if ("decision".equals(target.type())) applyToDecision(p, target.name());
        else if ("parameter".equals(target.type())) applyToParameter(p, target.name());
        else throw new ValidationException("Unknown artifact type '" + target.type() + "' on proposal " + p.proposalId());

        proposals.promote(p.proposalId(), Instant.now());
        if (registry.descriptor(learner.learnerKey()).isPresent()) learners.recordRun(learner.learnerKey(), Instant.now());
        events.proposalPromoted(p.proposalId(), Actor.system("promotion-service"),
            Map.of("target", target.toString(), "tier", p.tier().name(), "reason", reason, "learner", learner.learnerKey()));
    }

    private void applyToDecision(Proposal p, String decisionName) {
        if (p.changeset().changes().size() != 1 || !(p.changeset().changes().get(0) instanceof PolicyChange.ThresholdChange tc)) {
            throw new ConflictException("Automated promotion only applies ThresholdChange against a decision; promote '" + decisionName
                + "' manually through the Decision Studio for this change type");
        }
        DecisionDefinition draft = decisionStudio.createDraft(decisionName, liveYaml(decisionName));
        decisionStudio.edit(decisionName, draft.version(), Map.of("bands." + tc.band().toLowerCase(java.util.Locale.ROOT), tc.to()));
        decisionStudio.lint(decisionName, draft.version());
        decisionStudio.evaluate(decisionName, draft.version());
        // T0/T1-at-L3 auto-promotion bypasses the Decision Studio's own human-approval count by construction (this
        // IS the system's approval, per EX-07) — publish directly rather than routing through submitForApproval,
        // which would deadlock waiting for a human approver that auto-promotion is explicitly meant to skip.
        decisionStudio.publish(decisionName, draft.version(), "live", true);
    }

    private String liveYaml(String decisionName) {
        return decisionStudio.versions(decisionName).stream().filter(r -> "live".equals(r.definition().status())).findFirst()
            .orElseThrow(() -> new ConflictException("Decision " + decisionName + " has no live version to base a promotion on"))
            .definition().yaml();
    }

    private void applyToParameter(Proposal p, String parameterRef) {
        if (parameterRef.startsWith("live:")) { applyLiveUse(p, parameterRef.substring("live:".length())); return; }
        // Convention: a parameter ref of the form "<subjectType>:<subjectId>:<factKey>" names the learned fact a
        // ParameterChange moves (e.g. "NODE:node_12:SAFETY_STOCK_UNITS"). Anything else can't be applied
        // automatically — it's a parameter this build doesn't know how to write back.
        String[] parts = parameterRef.split(":", 3);
        if (parts.length != 3 || !(p.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc)) {
            throw new ConflictException("Automated promotion only applies a ParameterChange against a 'subjectType:subjectId:factKey' parameter ref; got '" + parameterRef + "'");
        }
        SubjectType st;
        FactKey key;
        try { st = SubjectType.valueOf(parts[0]); key = FactKey.valueOf(parts[2]); }
        catch (IllegalArgumentException e) { throw new ConflictException("Unrecognised subjectType/factKey in parameter ref '" + parameterRef + "'"); }
        Instant now = Instant.now();
        var current = facts.active(st, parts[1], key);
        int nextVersion = current.map(f -> f.version() + 1).orElse(1);
        facts.supersede(st, parts[1], key, now);
        TypedValue value = key.shape() == TypedValue.NumberValue.class
            ? new TypedValue.NumberValue(pc.to() instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(pc.to())))
            : current.map(LearnedFact::value).orElseThrow(() -> new ConflictException("No active fact to base a non-numeric ParameterChange on"));
        facts.insert(new LearnedFact(Ids.prefixed("fact"), st, parts[1], key, value, 1.0, 0, "promotion-service", nextVersion, now, null, now));
        events.factLearned(parts[1], Actor.system("promotion-service"), Map.of("subjectType", st.name(), "factKey", key.name(), "version", nextVersion, "proposalId", p.proposalId()));
    }

    /**
     * Enabling live use of a learned parameter family ({@code parameter:live:<family>}): the change's {@code to} is
     * {@code {"enabled": true, "min": .., "max": .., "maxStep": .., "learner": ..}}; {@code enabled: false} revokes.
     */
    private void applyLiveUse(Proposal p, String family) {
        if (!(p.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc) || !(pc.to() instanceof Map<?, ?> to)) {
            throw new ConflictException("A live-use change needs a ParameterChange whose 'to' is {enabled, min, max, maxStep}");
        }
        String by = "proposal:" + p.proposalId();
        if (Boolean.FALSE.equals(to.get("enabled"))) { parameters.revoke(family, by); return; }
        String learner = to.get("learner") == null ? learnerKeyOf(p) : String.valueOf(to.get("learner"));
        parameters.promote(family, learner, num(to.get("min")), num(to.get("max")), num(to.get("maxStep")), p.proposalId(), by);
    }

    /** The governing learner's state; a proposal a person authored has no learner, so only tier rules apply to it. */
    private LearnerState learnerFor(Proposal p) {
        String key = learnerKeyOf(p);
        if (registry.descriptor(key).isPresent()) return learners.getOrCreate(key);
        return new LearnerState(key, AutonomyLevel.L1_RECOMMEND, true, Integer.MAX_VALUE, null, null, "system", Instant.now(), null);
    }

    /** Proposals are authored as {@code system:<learner_key>}; the governance row is keyed by the bare learner key. */
    static String learnerKeyOf(Proposal p) {
        String by = p.createdBy() == null ? "" : p.createdBy();
        return by.startsWith("system:") ? by.substring("system:".length()) : by;
    }

    private static Double num(Object o) { return o instanceof Number n ? n.doubleValue() : o == null ? null : Double.valueOf(String.valueOf(o)); }

    private int countPromotedInTrailingWeek(String learnerKey) {
        Instant since = Instant.now().minus(Duration.ofDays(7));
        return (int) proposals.byStatus("PROMOTED", 500).stream()
            .filter(p -> learnerKey.equals(learnerKeyOf(p)) && p.promotedAt() != null && p.promotedAt().isAfter(since)).count();
    }

    /** Same pattern as {@code DecisionEscalationTaskSubscriber.runAsSystem}: the sweep runs with no principal, and
     * {@code DecisionStudioService}'s {@code requireHuman()} only excludes DECISION/AGENT types, so SYSTEM passes. */
    private <T> T runAsSystem(java.util.function.Supplier<T> body) {
        Authentication prev = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(TenantContext.require())));
        try { return body.get(); }
        catch (RuntimeException e) { log.warn("Auto-promotion failed, leaving proposal for manual handling: {}", e.toString()); return null; }
        finally { if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev); }
    }

    /** GV-05: tenant-defined freeze windows live in {@code objective_profile.constraints.freezeWindows}, each
     * {@code {"from": "yyyy-MM-dd", "to": "yyyy-MM-dd"}}. */
    static final class FreezeWindows {
        record Window(java.time.LocalDate from, java.time.LocalDate to) {}

        @SuppressWarnings("unchecked")
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
