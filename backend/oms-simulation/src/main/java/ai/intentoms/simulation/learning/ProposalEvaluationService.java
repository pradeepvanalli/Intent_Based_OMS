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

    @Value("${oms.learning.replay.max-resimulated:300}") private int maxResimulated;
    @Value("${oms.learning.replay.min-cohort:20}") private int minCohort;

    public ProposalEvaluationService(ProposalRepository proposals, DecisionRecordRepository decisionRecords, DecisionDefinitionRepository definitions,
                                     DecisionDefinitionParser parser, DecisionRuntime runtime, EpisodeRepository episodes, LearnerRegistry registry, LearningEvents events) {
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
        ArtifactRef target = proposal.changeset().target();
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
        return r;
    }

    record Paired(DecisionRecord record, LoggedRecord logged) {}

    /** Logged LIVE records of a version whose episode has a reward, newest first (OC-04: only episodes past OPEN). */
    private List<Paired> pairedRecords(String decision, int version) {
        List<Paired> out = new ArrayList<>();
        for (DecisionRecord r : decisionRecords.recentLive(decision, version, Instant.now().minus(LOOKBACK), MAX_RECORDS)) {
            Optional<Episode> ep = episodes.findByDecisionId(r.id());
            if (ep.isEmpty() || ep.get().reward() == null) continue;
            boolean acted = r.band() == Band.ACT || "T0".equals(String.valueOf(r.details() == null ? null : r.details().get("tier")));
            String arm = !acted ? NOT_ACTED : r.chosenArm() != null ? r.chosenArm() : String.valueOf(r.value());
            out.add(new Paired(r, new LoggedRecord(arm, r.propensity(), r.confidence(), r.band().name(), ep.get().reward())));
        }
        return out;
    }

    // ------------------------------------------------------------------ config and parameter targets

    private Map<String, Object> evaluateNonDecision(Proposal proposal, ArtifactRef target) {
        String ref = target.name();
        if ("config".equals(target.type()) && ref.startsWith(PromotedProviderRouter.KEY_PREFIX) && proposal.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc) {
            String decision = ref.substring(PromotedProviderRouter.KEY_PREFIX.length());
            String routed = String.valueOf(pc.to());
            return cohorts(decision, r -> routed.equals(r.provider()), "decisions answered by " + routed, "decisions answered by other providers");
        }
        if ("parameter".equals(target.type()) && ref.startsWith("live:")) {
            String family = ref.substring("live:".length());
            List<String> decisions = registry.descriptors().stream().filter(d -> d.liveParameters().contains(family)).flatMap(d -> d.outcomeDecisions().stream()).distinct().toList();
            if (decisions.isEmpty()) return noCounterfactual(0, "no learner declares that it feeds " + family);
            return cohorts(decisions.get(0), ProposalEvaluationService::servedLearnedValue, "decisions that used a learned value", "decisions on static values");
        }
        int sample = 0;
        String learner = learnerKeyOf(proposal);
        for (String decision : registry.descriptor(learner).map(LearnerDescriptor::outcomeDecisions).orElse(List.of())) {
            sample += decisionRecords.recentLive(decision, latestVersion(decision), Instant.now().minus(LOOKBACK), MAX_RECORDS).size();
        }
        return noCounterfactual(sample, target + " has only ever run at its current value, so logged decisions cannot show what the new value would do");
    }

    private Map<String, Object> cohorts(String decision, Predicate<DecisionRecord> treated, String treatedLabel, String controlLabel) {
        List<Double> t = new ArrayList<>(), c = new ArrayList<>();
        for (Paired p : pairedRecords(decision, latestVersion(decision))) (treated.test(p.record()) ? t : c).add(p.logged().reward());
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

    private static String learnerKeyOf(Proposal p) {
        String by = p.createdBy() == null ? "" : p.createdBy();
        return by.startsWith("system:") ? by.substring("system:".length()) : by;
    }

    private static double var(List<Double> xs, double m) {
        if (xs.size() < 2) return 0;
        double s = 0;
        for (double x : xs) s += (x - m) * (x - m);
        return s / (xs.size() - 1);
    }

    private static double round(double v) { return Math.round(v * 10_000d) / 10_000d; }
}
