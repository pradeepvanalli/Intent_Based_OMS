package ai.intentoms.simulation.learning;

import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionRuntime;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.hooks.PromotedProviderRouter;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.promotion.ChangesetApplier;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * EX-01: evaluates a proposal against up to 90 days of logged LIVE decisions whose episodes carry a reward, before any
 * live exposure, and writes the result as the proposal's evaluation report.
 *
 * <ul>
 *   <li><b>Decision targets</b>, every change type. A pure act-threshold change is simulated from the logged confidence.
 *       Anything else (weights, rules added or retired, provider or other decision fields) is applied to the version the
 *       proposal was drafted against ({@link ChangesetApplier}) and the logged inputs are re-run through that candidate in
 *       simulation mode; the arm it would act on feeds the same inverse-propensity / doubly-robust estimator as the logged
 *       propensities (OC-01).</li>
 *   <li><b>Config and parameter targets</b> have no decision version to re-run. Where both behaviours have actually
 *       been observed (a provider the decision already answers with, a learned value already served somewhere) the
 *       report compares those cohorts and says it is observational. Where only the current behaviour has ever been
 *       observed, the report says no counterfactual exists and carries no lift, so the promotion gate requires a person
 *       (and a shadow run) instead of guessing.</li>
 * </ul>
 */
@Service
public class ProposalEvaluationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProposalEvaluationService.class);
    private static final Duration LOOKBACK = Duration.ofDays(90);
    private static final int MAX_RECORDS = 5000;
    /** The arm of a decision that did not act (confirm or escalate to a person): its own arm, on both the logged and proposed side. */
    static final String NOT_ACTED = "__not_acted__";

    private final ProposalRepository proposals;
    private final DecisionRecordRepository decisionRecords;
    private final DecisionDefinitionRepository definitions;
    private final DecisionDefinitionParser parser;
    private final DecisionRuntime runtime;
    private final EpisodeRepository episodes;
    private final LearnerRegistry registry;
    private final LearningEvents events;
    private final ai.intentoms.learning.promotion.HardConstraints hardConstraints;
    private final ai.intentoms.learning.promotion.ProposalOwnership ownership;
    private ai.intentoms.learning.store.LearnedFactRepository facts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFacts(ai.intentoms.learning.store.LearnedFactRepository facts) { this.facts = facts; }

    @Value("${oms.learning.replay.max-resimulated:300}") private int maxResimulated;
    @Value("${oms.learning.replay.min-cohort:20}") private int minCohort;

    public ProposalEvaluationService(ProposalRepository proposals, DecisionRecordRepository decisionRecords, DecisionDefinitionRepository definitions,
                                     DecisionDefinitionParser parser, DecisionRuntime runtime, EpisodeRepository episodes, LearnerRegistry registry, LearningEvents events) {
        this(proposals, decisionRecords, definitions, parser, runtime, episodes, registry, events, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProposalEvaluationService(ProposalRepository proposals, DecisionRecordRepository decisionRecords, DecisionDefinitionRepository definitions,
                                     DecisionDefinitionParser parser, DecisionRuntime runtime, EpisodeRepository episodes, LearnerRegistry registry, LearningEvents events,
                                     ai.intentoms.learning.promotion.HardConstraints hardConstraints) {
        this.hardConstraints = hardConstraints;
        this.ownership = new ai.intentoms.learning.promotion.ProposalOwnership(registry);
        this.proposals = proposals;
        this.decisionRecords = decisionRecords;
        this.definitions = definitions;
        this.parser = parser;
        this.runtime = runtime;
        this.episodes = episodes;
        this.registry = registry;
        this.events = events;
    }

    public Proposal evaluate(String proposalId, String actorRef) {
        Proposal proposal = proposals.get(proposalId);
        // Only a draft or an evaluated change is (re-)tested; an approved, live, rejected or rolled-back one is not reopened.
        ai.intentoms.learning.model.ProposalLifecycle.require(proposal, ai.intentoms.learning.model.ProposalStatus.EVALUATED, "re-test");
        // §7: a change that breaches a hard constraint is refused before any evaluation, never traded off against reward.
        if (hardConstraints != null) {
            var violation = hardConstraints.check(proposal.changeset());
            if (violation.isPresent()) {
                String reason = "Hard constraint " + violation.get().code() + ": " + violation.get().reason();
                proposals.reject(proposalId, reason, Instant.now());
                events.proposalRejected(proposalId, Actor.parse(actorRef), Map.of("target", proposal.changeset().target().toString(), "reason", reason,
                    "constraint", violation.get().code()));
                return proposals.get(proposalId);
            }
        }
        ArtifactRef target = proposal.changeset().target();
        // D-2 (GV-02): a decision change drafted against another version, or from a value no longer live, is not re-tested.
        if ("decision".equals(target.type())) {
            definitions.byStatus(target.name(), "live").stream().findFirst()
                .map(r -> ai.intentoms.learning.promotion.LiveBase.of(r.definition()).staleness(proposal.changeset()))
                .ifPresent(stale -> { throw new ai.intentoms.platform.error.ConflictException("Cannot evaluate " + proposalId + ": this change is out of date: " + stale); });
        }
        Map<String, Object> report = switch (target.type()) {
            case "decision" -> evaluateDecision(proposal, target.name());
            case "config", "parameter" -> evaluateNonDecision(proposal, target);
            default -> throw new ValidationException("Cannot evaluate a proposal targeting " + target);
        };
        report.put("changeTypes", proposal.changeset().changes().stream().map(c -> c.getClass().getSimpleName()).toList());
        report.put("evaluatedAt", Instant.now().toString());
        proposals.markEvaluated(proposalId, report, Instant.now());
        events.proposalEvaluated(proposalId, Actor.parse(actorRef), report);
        return proposals.get(proposalId);
    }

    // ------------------------------------------------------------------ decision targets

    private Map<String, Object> evaluateDecision(Proposal proposal, String decision) {
        List<PolicyChange> changes = proposal.changeset().changes();
        if (!ChangesetApplier.decisionApplicable(changes)) throw new ValidationException("Proposal " + proposal.proposalId() + " has changes that do not apply to decision " + decision);
        int baseVersion = proposal.changeset().baseVersion();
        List<Paired> paired = pairedRecords(decision, baseVersion);
        boolean thresholdOnly = changes.stream().allMatch(c -> c instanceof PolicyChange.ThresholdChange);
        if (thresholdOnly) {
            // The proposed act threshold applied to each logged confidence: acting now means the model's answer, not acting
            // is its own arm, except on a T0 decision, which below its act threshold still acts with the escape value.
            Double newAct = null;
            for (PolicyChange c : changes) if (c instanceof PolicyChange.ThresholdChange tc && "act".equalsIgnoreCase(tc.band())) newAct = tc.to();
            DecisionDefinition baseDef = definitions.find(decision, baseVersion).map(DecisionDefinitionRepository.Row::definition).orElse(null);
            String escape = baseDef != null && baseDef.tier() == Tier.T0 && baseDef.escapeValue() != null ? String.valueOf(baseDef.escapeValue()) : null;
            Map<LoggedRecord, String> proposedArm = new IdentityHashMap<>();
            int flips = 0;
            for (Paired p : paired) {
                boolean acts = newAct == null ? !NOT_ACTED.equals(p.logged().chosenArm()) : p.record().confidence() >= newAct || p.record().layer() == ai.intentoms.decision.model.Layer.L0_RULE;
                Object model = modelAnswer(p.record());
                String arm = acts && model != null ? String.valueOf(model) : escape != null ? escape : NOT_ACTED;
                proposedArm.put(p.logged(), arm);
                if (!arm.equals(p.logged().chosenArm())) flips++;
            }
            List<LoggedRecord> loggedForThreshold = paired.stream().map(Paired::logged).toList();
            // D4-1/EX-01: this decision's own exploration floor may log a true propensity below the default clip
            // (an explored arm among many), so the clip used here is never tighter than that floor actually draws.
            double floor = propensityFloor(decision, baseDef == null ? null : baseDef.tier(), paired);
            Map<String, Object> r = new LinkedHashMap<>(OffPolicyEvaluator.evaluate(loggedForThreshold, proposedArm::get, floor).asMap());
            r.put("simulation", "proposed act threshold applied to the logged confidence");
            r.put("decisionsChanged", flips);
            r.put("rewards", rewardMix(paired));
            r.put("loggedPropensities", propensityMixWithTierNote(loggedForThreshold, floor, decision, baseDef == null ? null : baseDef.tier()));
            Double act = newAct;
            addFraudLabels(r, decision, rec -> FraudLabelEvaluation.risky(rec.value())
                && (rec.layer() == ai.intentoms.decision.model.Layer.L0_RULE || (act == null ? rec.band() == Band.ACT : rec.confidence() >= act)));
            return r;
        }
        DecisionDefinition base = definitions.find(decision, baseVersion).or(() -> definitions.byStatus(decision, "live").stream().findFirst())
            .orElseThrow(() -> new ValidationException("Decision " + decision + " has no version " + baseVersion + " or live version to replay against")).definition();
        DecisionDefinition candidate = parser.parse(ChangesetApplier.applyToDecision(base.yaml(), changes));
        List<Paired> replayed = paired.size() > maxResimulated ? paired.subList(0, maxResimulated) : paired;
        Map<LoggedRecord, String> proposedArm = new IdentityHashMap<>();
        int flips = 0;
        for (Paired p : replayed) {
            DecisionResult sim = runtime.evaluate(candidate, p.record().input(), new DecisionOptions(p.record().subjectType(), p.record().subjectId(),
                p.record().correlationId(), Mode.SIMULATION, base.version(), Actor.system("proposal-replay"), Map.of()));
            boolean acts = sim.band() == Band.ACT || candidate.tier() == Tier.T0;
            String arm = acts && sim.value() != null ? String.valueOf(sim.value()) : NOT_ACTED;
            proposedArm.put(p.logged(), arm);
            if (!arm.equals(p.logged().chosenArm())) flips++;
        }
        List<LoggedRecord> logged = replayed.stream().map(Paired::logged).toList();
        // D5-7/EX-01: the resimulation path replays the same logged records as the threshold-only path above, so it
        // must use the same per-decision dynamic propensity-clip floor, not the global default — an actively-exploring
        // T0 decision's true (lower) propensity must not be clipped away just because the candidate is a rule/weight/
        // provider change rather than a threshold change.
        double floor = propensityFloor(decision, candidate.tier(), replayed);
        Map<String, Object> r = new LinkedHashMap<>(OffPolicyEvaluator.evaluate(logged, proposedArm::get, floor).asMap());
        r.put("method", "resimulation+ips_doubly_robust");
        r.put("simulation", "logged inputs re-run through the candidate definition in simulation mode");
        r.put("replayed", replayed.size());
        r.put("decisionsChanged", flips);
        r.put("baseVersion", base.version());
        r.put("rewards", rewardMix(replayed));
        r.put("loggedPropensities", propensityMixWithTierNote(logged, floor, decision, candidate.tier()));
        // FD-04 / C-N1: like with like. Each labelled order is re-run through the candidate and through the base it was
        // drafted against, with the same provider; only where the change itself turns the outcome does the proposed
        // policy differ from what was logged. Comparing the logged answer with today's provider re-simulating the
        // candidate would charge the rule with every difference between the two providers (a rule matching no labelled
        // order used to "lose" every fraud the logged model had caught). Up to the same cap; beyond it the logged answer stands.
        int[] simulated = {0};
        Map<String, Boolean> proposedByRecord = new java.util.HashMap<>();
        addFraudLabels(r, decision, FraudLabelEvaluation::loggedBlocks, rec -> proposedByRecord.computeIfAbsent(rec.id(), id -> {
            if (simulated[0]++ >= maxResimulated) return FraudLabelEvaluation.loggedBlocks(rec);
            boolean withChange = blocks(runtime.evaluate(candidate, rec.input(), simulationOf(rec, base.version())), candidate);
            boolean withoutChange = blocks(runtime.evaluate(base, rec.input(), simulationOf(rec, base.version())), base);
            return withChange == withoutChange ? FraudLabelEvaluation.loggedBlocks(rec) : withChange;
        }));
        return r;
    }

    private static DecisionOptions simulationOf(DecisionRecord rec, int version) {
        return new DecisionOptions(rec.subjectType(), rec.subjectId(), rec.correlationId(), Mode.SIMULATION, version, Actor.system("proposal-replay"), Map.of());
    }

    /** Whether a simulated answer would block the order: it says "risky" and acts on it (a T0 always acts). */
    private static boolean blocks(DecisionResult sim, DecisionDefinition def) {
        return FraudLabelEvaluation.risky(sim.value()) && (sim.band() == Band.ACT || def.tier() == Tier.T0);
    }

    /** FD-04: a fraud decision's change is also scored on its labelled fraud history. */
    private void addFraudLabels(Map<String, Object> report, String decision, Predicate<DecisionRecord> proposedBlocks) {
        addFraudLabels(report, decision, FraudLabelEvaluation::loggedBlocks, proposedBlocks);
    }

    private void addFraudLabels(Map<String, Object> report, String decision, Predicate<DecisionRecord> currentBlocks, Predicate<DecisionRecord> proposedBlocks) {
        if (fraudLabels == null || !FraudLabelEvaluation.FRAUD_DECISIONS.contains(decision)) return;
        try {
            report.put("fraudLabels", fraudLabels.evaluate(decision, currentBlocks, proposedBlocks));
        } catch (RuntimeException e) {
            log.warn("Labelled-history evaluation of {} failed: {}", decision, e.toString());
            report.put("fraudLabels", Map.of("method", "fraud_label_replay", "sufficient", false, "reason", "could not be computed: " + e.getMessage()));
        }
    }

    private FraudLabelEvaluation fraudLabels;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFraudLabels(FraudLabelEvaluation fraudLabels) { this.fraudLabels = fraudLabels; }

    private ai.intentoms.learning.hooks.ExplorationFloor explorationFloor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExplorationFloor(ai.intentoms.learning.hooks.ExplorationFloor explorationFloor) { this.explorationFloor = explorationFloor; }

    /**
     * The model's own answer on a logged record: what it applied, unless the applied arm was explored or a handed-off
     * default ({@code details.modelAnswer}), or a T0 decision fell back to its escape value below the act threshold (the
     * main question's answer).
     */
    static Object modelAnswer(DecisionRecord r) {
        Map<String, Object> d = r.details() == null ? Map.of() : r.details();
        if (d.get("modelAnswer") != null && !String.valueOf(d.get("modelAnswer")).isBlank()) return d.get("modelAnswer");
        if ("T0".equals(String.valueOf(d.get("tier"))) && d.get("mainQuestion") != null && r.answers() != null) {
            ai.intentoms.decision.model.Answer a = r.answers().get(String.valueOf(d.get("mainQuestion")));
            if (a != null && a.value() != null) return a.value();
        }
        return r.value();
    }

    /** How many of the logged choices carry a real (below 1) propensity, and the smallest one: IPS only corrects with those. */
    static Map<String, Object> propensityMix(List<LoggedRecord> logged) {
        return propensityMix(logged, OffPolicyEvaluator.PROPENSITY_FLOOR);
    }

    /** As {@link #propensityMix(List)}, naming the actual clip used (D4-1/EX-01: a decision's own exploration floor
     * can lower it below the default). */
    static Map<String, Object> propensityMix(List<LoggedRecord> logged, double clippedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("records", logged.size());
        m.put("belowOne", logged.stream().filter(l -> l.propensity() < 1.0 - 1e-9).count());
        m.put("min", round(logged.stream().mapToDouble(LoggedRecord::propensity).min().orElse(1.0)));
        m.put("clippedAt", clippedAt);
        return m;
    }

    /**
     * {@link #propensityMix(List, double)} plus, for a decision that cannot explore at all, a {@code note} naming why
     * every logged propensity is 1.0 (EX-01 note): T1/T2+ decisions never explore (§7 hard constraints forbid
     * exploring money, fraud, mandate and customer-affecting decisions, and a T1+ change needs approval before it can
     * go live) — this is deliberate, not a gap, so an auditor reading the report does not mistake it for one.
     */
    static Map<String, Object> propensityMixWithTierNote(List<LoggedRecord> logged, double clippedAt, String decision, Tier tier) {
        Map<String, Object> m = propensityMix(logged, clippedAt);
        if (tier != null && !ai.intentoms.learning.hooks.ExplorationFloor.allowed(decision, tier)) {
            m.put("note", tier == Tier.T0
                ? "every record logs propensity 1.0 by design: this decision is excluded from exploration (§7 hard constraints forbid exploring money, fraud, mandate and customer-affecting decisions)"
                : "every record logs propensity 1.0 by design: " + tier + " decisions do not explore (§7 hard constraints; only T0 decisions that act on their own may, and a " + tier
                    + "+ change needs approval before it can go live)");
        }
        return m;
    }

    /**
     * D4-1/EX-01: the propensity-clip floor to use for this decision's logged records: the global default, or this
     * decision's own exploration floor when it can legitimately log a true propensity below it, so an explored record
     * is never clipped away by a floor tighter than the governed share actually draws (clip = min(default, share /
     * (arms − 1))).
     */
    private double propensityFloor(String decision, Tier tier, List<Paired> paired) {
        if (explorationFloor == null || tier == null) return OffPolicyEvaluator.PROPENSITY_FLOOR;
        int arms = paired.stream().map(p -> p.record().arms()).filter(java.util.Objects::nonNull).mapToInt(List::size).max().orElse(0);
        return Math.min(OffPolicyEvaluator.PROPENSITY_FLOOR, explorationFloor.minPropensity(decision, tier, arms));
    }

    // ------------------------------------------------------------------ the sourcing bandit's policy (EX-01 a)

    static final String BANDIT = "sourcing_policy_bandit";
    static final String BANDIT_EXPLORATION = "learning.bandit.exploration";
    static final String BANDIT_LIVE = "live:sourcing.policy_bandit";

    /**
     * EX-01 (a): a change to the sourcing bandit's policy (its exploration share, or switching it on or off) is evaluated
     * with IPS and doubly robust over the bandit's logged choices, which carry the true probability of the policy each
     * order got, instead of being called counterfactual-free. The target policy's probability of each logged arm comes
     * from the record itself (its eligible arms, weights and exploration share); overridden draws are left out.
     */
    private Optional<Map<String, Object>> banditPolicy(Proposal proposal, ArtifactRef target) {
        if (proposal.changeset().changes().isEmpty() || !(proposal.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc)) return Optional.empty();
        java.util.function.Function<DecisionRecord, Map<String, Double>> policy;
        String describe;
        if ("config".equals(target.type()) && BANDIT_EXPLORATION.equals(target.name()) && pc.to() instanceof Number to) {
            double eps = Math.max(0.0, Math.min(0.10, to.doubleValue()));
            policy = r -> epsilonGreedy(r, eps);
            describe = "the bandit exploring " + Math.round(eps * 1000) / 10.0 + "% of orders, with the weights each order was drawn with";
        } else if ("parameter".equals(target.type()) && BANDIT_LIVE.equals(target.name()) && pc.to() instanceof Map<?, ?> to) {
            if (Boolean.FALSE.equals(to.get("enabled"))) {
                policy = ProposalEvaluationService::defaultOnly;
                describe = "the bandit off: every order gets its default policy";
            } else {
                policy = r -> epsilonGreedy(r, r.details() != null && r.details().get("exploration") instanceof Number e ? e.doubleValue() : 0.10);
                describe = "the bandit acting with the exploration share and weights each order was drawn with";
            }
        } else return Optional.empty();
        List<Paired> paired = new ArrayList<>();
        Map<LoggedRecord, Map<String, Double>> targetOf = new IdentityHashMap<>();
        for (DecisionRecord r : decisionRecords.recentLive(BANDIT, 1, Instant.now().minus(LOOKBACK), MAX_RECORDS)) {
            if (r.details() != null && Boolean.TRUE.equals(r.details().get("overridden"))) continue; // the bandit's arm was not what the customer got
            if (r.chosenArm() == null) continue;
            Optional<Episode> ep = episodes.findByDecisionId(r.id());
            if (ep.isEmpty() || ep.get().reward() == null) continue;
            Map<String, Double> t = policy.apply(r);
            if (t == null) continue;
            LoggedRecord l = new LoggedRecord(r.chosenArm(), r.propensity(), r.confidence(), r.band().name(), ep.get().reward());
            paired.add(new Paired(r, l, ep.get().status() == ai.intentoms.learning.model.EpisodeStatus.MATURE));
            targetOf.put(l, t);
        }
        List<LoggedRecord> logged = paired.stream().map(Paired::logged).toList();
        if (logged.size() < minCohort) {
            return Optional.of(noCounterfactual(logged.size(), "only " + logged.size() + " rewarded sourcing-policy choices with a logged propensity (need " + minCohort + ")"));
        }
        Map<String, Object> r = new LinkedHashMap<>(OffPolicyEvaluator.evaluateStochastic(logged, targetOf::get).asMap());
        r.put("method", "ips_doubly_robust_logged_propensities");
        r.put("decision", BANDIT);
        r.put("targetPolicy", describe);
        r.put("simulation", "each logged sourcing-policy choice weighted by the target policy's probability of it over the logged propensity (clipped at "
            + OffPolicyEvaluator.PROPENSITY_FLOOR + ")");
        r.put("rewards", rewardMix(paired));
        r.put("loggedPropensities", propensityMix(logged));
        return Optional.of(r);
    }

    /** Epsilon-greedy over the record's eligible arms with its weights: the top arm gets 1 - eps plus its uniform share. */
    @SuppressWarnings("unchecked")
    static Map<String, Double> epsilonGreedy(DecisionRecord r, double eps) {
        List<String> arms = r.arms() == null ? List.of() : r.arms();
        if (arms.isEmpty()) return null;
        Map<String, Object> w = r.details() != null && r.details().get("weights") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String top = arms.stream().max((a, b) -> Double.compare(num(w.get(a)), num(w.get(b)))).orElse(arms.get(0));
        Map<String, Double> out = new LinkedHashMap<>();
        for (String a : arms) out.put(a, (a.equals(top) ? 1.0 - eps : 0.0) + (arms.size() == 1 ? eps : eps / arms.size()));
        return out;
    }

    /** The bandit off: the order's default policy, known from the record ({@code details.defaultPolicy}, or what a non-acting draw applied). */
    static Map<String, Double> defaultOnly(DecisionRecord r) {
        Map<String, Object> d = r.details() == null ? Map.of() : r.details();
        Object dflt = d.get("defaultPolicy") != null ? d.get("defaultPolicy") : Boolean.FALSE.equals(d.get("acted")) ? d.get("appliedPolicy") : null;
        return dflt == null ? null : Map.of(String.valueOf(dflt), 1.0);
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    /** {@code mature} episodes have a final reward; {@code provisional} ones may still change as late outcomes land (OC-04). */
    record Paired(DecisionRecord record, LoggedRecord logged, boolean mature) {}

    /** How much of the evidence rests on final rewards versus provisional ones (EX-01 reporting, OC-04). */
    static Map<String, Object> rewardMix(List<Paired> paired) {
        long mature = paired.stream().filter(Paired::mature).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mature", mature);
        m.put("provisional", paired.size() - mature);
        m.put("matureShare", paired.isEmpty() ? 0.0 : round((double) mature / paired.size()));
        return m;
    }

    /** Logged LIVE records of a version whose episode has a reward, newest first (OC-04: only episodes past OPEN). */
    private List<Paired> pairedRecords(String decision, int version) {
        List<Paired> out = new ArrayList<>();
        for (DecisionRecord r : decisionRecords.recentLive(decision, version, Instant.now().minus(LOOKBACK), MAX_RECORDS)) {
            Optional<Episode> ep = episodes.findByDecisionId(r.id());
            if (ep.isEmpty() || ep.get().reward() == null) continue;
            boolean acted = r.band() == Band.ACT || "T0".equals(String.valueOf(r.details() == null ? null : r.details().get("tier")));
            String arm = !acted ? NOT_ACTED : r.chosenArm() != null ? r.chosenArm() : String.valueOf(r.value());
            out.add(new Paired(r, new LoggedRecord(arm, r.propensity(), r.confidence(), r.band().name(), ep.get().reward()),
                ep.get().status() == ai.intentoms.learning.model.EpisodeStatus.MATURE));
        }
        return out;
    }

    // ------------------------------------------------------------------ config and parameter targets

    private Map<String, Object> evaluateNonDecision(Proposal proposal, ArtifactRef target) {
        String ref = target.name();
        // FS-04: a sourcing-weight proposal arrives with a plan-rescoring replay (recorded plans re-scored under the
        // new weights, with a 95% interval). That is real counterfactual evidence; keep it rather than replacing it
        // with an observational fallback.
        Map<String, Object> existing = proposal.evaluationReport();
        if (existing != null && "plan_rescoring_replay".equals(existing.get("method"))) return new java.util.LinkedHashMap<>(existing);
        Optional<Map<String, Object>> bandit = banditPolicy(proposal, target);
        if (bandit.isPresent()) return bandit.get();
        if ("config".equals(target.type()) && ref.startsWith(PromotedProviderRouter.KEY_PREFIX) && proposal.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc) {
            String decision = ref.substring(PromotedProviderRouter.KEY_PREFIX.length());
            String routed = String.valueOf(pc.to());
            return cohorts(decision, r -> routed.equals(r.provider()), "decisions answered by " + routed, "decisions answered by other providers");
        }
        if ("parameter".equals(target.type()) && ref.startsWith("live:")) {
            String family = ref.substring("live:".length());
            // N6: say what is actually missing: a learner, a decision to compare on, or rewarded decisions on each side.
            List<String> feeders = registry.descriptors().stream().filter(d -> d.liveParameters().contains(family)).map(LearnerDescriptor::key).toList();
            if (feeders.isEmpty()) return noCounterfactual(0, "no learner learns " + family + ", so there is no learned value to serve");
            if (facts != null && enables(proposal) && facts.countCurrentByLearners(feeders) == 0) {
                return noCounterfactual(0, String.join(", ", feeders) + " has not learned any value for " + family
                    + " yet, so live decisions would keep the static value until it does");
            }
            List<String> decisions = ownership.watchedDecisions(proposal);
            if (decisions.isEmpty()) {
                return noCounterfactual(0, String.join(", ", feeders) + " learns " + family + ", but no decision that reads it records outcomes, so served and static values cannot be compared");
            }
            int sample = 0;
            List<String> missing = new ArrayList<>();
            for (String decision : decisions) {
                Map<String, Object> r = cohorts(decision, ProposalEvaluationService::servedLearnedValue, "decisions that used a learned value", "decisions on static values");
                if (!"no_counterfactual".equals(r.get("method"))) return r;
                sample += ((Number) r.get("sampleSize")).intValue();
                missing.add(decision + ": " + r.get("reason"));
            }
            return noCounterfactual(sample, "not enough rewarded decisions on both learned and static values yet (" + String.join("; ", missing) + ")");
        }
        int sample = 0;
        List<String> decisions = ownership.watchedDecisions(proposal);
        for (String decision : decisions) {
            sample += decisionRecords.recentLive(decision, latestVersion(decision), Instant.now().minus(LOOKBACK), MAX_RECORDS).size();
        }
        if (decisions.isEmpty()) return noCounterfactual(0, "no learner owns " + target + " and no decision is known to read it, so there is nothing to replay it on");
        return noCounterfactual(sample, target + " has only ever run at its current value, so the " + sample + " logged " + String.join(", ", decisions)
            + " decisions cannot show what the new value would do");
    }

    /** Whether a live-use proposal switches the family on (a revocation needs no learned value). */
    private static boolean enables(Proposal p) {
        return !(p.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc && pc.to() instanceof Map<?, ?> to && Boolean.FALSE.equals(to.get("enabled")));
    }

    private Map<String, Object> cohorts(String decision, Predicate<DecisionRecord> treated, String treatedLabel, String controlLabel) {
        List<Double> t = new ArrayList<>(), c = new ArrayList<>();
        List<Paired> all = pairedRecords(decision, latestVersion(decision));
        for (Paired p : all) (treated.test(p.record()) ? t : c).add(p.logged().reward());
        if (t.size() < minCohort || c.size() < minCohort) {
            return noCounterfactual(t.size() + c.size(), "only " + t.size() + " " + treatedLabel + " and " + c.size() + " " + controlLabel + " with a reward (need " + minCohort + " each)");
        }
        double mt = t.stream().mapToDouble(Double::doubleValue).average().orElse(0), mc = c.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double se = Math.sqrt(var(t, mt) / t.size() + var(c, mc) / c.size());
        double lift = mt - mc;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", "observational_cohort");
        r.put("decision", decision);
        r.put("sampleSize", t.size() + c.size());
        r.put("treated", Map.of("label", treatedLabel, "n", t.size(), "meanReward", round(mt)));
        r.put("control", Map.of("label", controlLabel, "n", c.size(), "meanReward", round(mc)));
        r.put("baselineReward", round(mc));
        r.put("proposedRewardEstimate", round(mt));
        r.put("liftEstimate", round(lift));
        r.put("confidenceInterval", Map.of("lower", round(lift - 1.96 * se), "upper", round(lift + 1.96 * se)));
        r.put("caveat", "cohorts were not randomised; confirm with a shadow run before promoting");
        r.put("rewards", rewardMix(all));
        return r;
    }

    private static Map<String, Object> noCounterfactual(int sample, String why) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", "no_counterfactual");
        r.put("sampleSize", sample);
        r.put("reason", why);
        r.put("next", "run a shadow experiment, then promote within the learner's max step; the promotion gate will ask for a person");
        return r;
    }

    @SuppressWarnings("unchecked")
    static boolean servedLearnedValue(DecisionRecord r) {
        Object facts = r.details() == null ? null : r.details().get("learnedFacts");
        if (!(facts instanceof List<?> l)) return false;
        return l.stream().anyMatch(f -> f instanceof Map<?, ?> m && "LEARNED".equals(String.valueOf(m.get("source"))));
    }

    private int latestVersion(String decision) {
        return definitions.byStatus(decision, "live").stream().findFirst().map(r -> r.definition().version()).orElse(0);
    }

    private static double var(List<Double> xs, double m) {
        if (xs.size() < 2) return 0;
        double s = 0;
        for (double x : xs) s += (x - m) * (x - m);
        return s / (xs.size() - 1);
    }

    private static double round(double v) { return Math.round(v * 10_000d) / 10_000d; }
}
