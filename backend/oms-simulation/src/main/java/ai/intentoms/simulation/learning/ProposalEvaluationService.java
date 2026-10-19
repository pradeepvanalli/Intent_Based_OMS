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
            // The proposed act threshold applied to each logged confidence: acting now means the logged value, not acting is its own arm.
            Double newAct = null;
            for (PolicyChange c : changes) if (c instanceof PolicyChange.ThresholdChange tc && "act".equalsIgnoreCase(tc.band())) newAct = tc.to();
            Map<LoggedRecord, String> proposedArm = new IdentityHashMap<>();
            int flips = 0;
            for (Paired p : paired) {
                boolean acts = newAct == null ? !NOT_ACTED.equals(p.logged().chosenArm()) : p.record().confidence() >= newAct || p.record().layer() == ai.intentoms.decision.model.Layer.L0_RULE;
                String arm = acts && p.record().value() != null ? String.valueOf(p.record().value()) : NOT_ACTED;
                proposedArm.put(p.logged(), arm);
                if (!arm.equals(p.logged().chosenArm())) flips++;
            }
            Map<String, Object> r = new LinkedHashMap<>(OffPolicyEvaluator.evaluate(paired.stream().map(Paired::logged).toList(), proposedArm::get).asMap());
            r.put("simulation", "proposed act threshold applied to the logged confidence");
            r.put("decisionsChanged", flips);
            r.put("rewards", rewardMix(paired));
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
        Map<String, Object> r = new LinkedHashMap<>(OffPolicyEvaluator.evaluate(logged, proposedArm::get).asMap());
        r.put("method", "resimulation+ips_doubly_robust");
        r.put("simulation", "logged inputs re-run through the candidate definition in simulation mode");
        r.put("replayed", replayed.size());
        r.put("decisionsChanged", flips);
        r.put("baseVersion", base.version());
        r.put("rewards", rewardMix(replayed));
        // FD-04: the labelled orders re-run through the candidate (up to the same cap; beyond it the logged answer stands).
        int[] simulated = {0};
        addFraudLabels(r, decision, rec -> {
            if (simulated[0]++ >= maxResimulated) return FraudLabelEvaluation.loggedBlocks(rec);
            DecisionResult sim = runtime.evaluate(candidate, rec.input(), new DecisionOptions(rec.subjectType(), rec.subjectId(),
                rec.correlationId(), Mode.SIMULATION, base.version(), Actor.system("proposal-replay"), Map.of()));
            return FraudLabelEvaluation.risky(sim.value()) && (sim.band() == Band.ACT || candidate.tier() == Tier.T0);
        });
        return r;
    }

    /** FD-04: a fraud decision's change is also scored on its labelled fraud history. */
    private void addFraudLabels(Map<String, Object> report, String decision, Predicate<DecisionRecord> proposedBlocks) {
        if (fraudLabels == null || !FraudLabelEvaluation.FRAUD_DECISIONS.contains(decision)) return;
        try {
            report.put("fraudLabels", fraudLabels.evaluate(decision, proposedBlocks));
        } catch (RuntimeException e) {
            log.warn("Labelled-history evaluation of {} failed: {}", decision, e.toString());
            report.put("fraudLabels", Map.of("method", "fraud_label_replay", "sufficient", false, "reason", "could not be computed: " + e.getMessage()));
        }
    }

    private FraudLabelEvaluation fraudLabels;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFraudLabels(FraudLabelEvaluation fraudLabels) { this.fraudLabels = fraudLabels; }

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
