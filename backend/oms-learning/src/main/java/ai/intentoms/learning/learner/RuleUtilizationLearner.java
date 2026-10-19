package ai.intentoms.learning.learner;

import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionRuntime;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.promotion.ChangesetApplier;
import ai.intentoms.learning.promotion.SystemPrincipal;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DF-07: proposes retiring L0 rules that no longer earn their place, as a reviewable {@code RuleRetire} proposal.
 * <ul>
 *   <li><b>Never fires:</b> a rule with zero fires across at least {@code min-sample} recent live decisions.</li>
 *   <li><b>L1 outperforms it on replay:</b> for a rule that does fire, the decisions it decided whose right answer is
 *       known are replayed without the rule (an in-memory candidate built from the live YAML, evaluated in simulation
 *       mode as the system principal, never acting). If the model alone is right at least five points more often than
 *       the rule was, retiring it is proposed with that evidence.</li>
 * </ul>
 * A rule decides without asking anyone, so its records almost never carry a person's resolution (every live rule-fired
 * record has requires_human false). The right answer therefore comes from whatever ground truth exists (audit DF-07):
 * <ul>
 *   <li>the decision's own outcome label, {@code fraud_outcome}, on the fraud decisions (a chargeback, a won dispute, a
 *       refused COD parcel, a return graded at the dock): the answer was right when it called the order risky exactly
 *       when it turned out to be fraud. Only labels {@link FraudLabels#sampled} counts are used: decisions old enough
 *       that their observation window ({@link FraudLabels#windowDays}, per decision) has closed, frauds and goods from
 *       the same cohort, so recent frauds next to unsettled goods cannot bias the comparison;</li>
 *   <li>otherwise a person's resolution of the record ({@code human_resolution}, people only).</li>
 * </ul>
 * One open proposal per decision at a time; evaluation, approval and promotion follow the normal gate.
 */
@Component
public class RuleUtilizationLearner implements GovernedLearner {
    public static final String KEY = "rule_utilization";
    private static final Logger log = LoggerFactory.getLogger(RuleUtilizationLearner.class);
    private static final String HUMAN_RESOLUTION_LABEL = "human_resolution";
    private static final int MAX_RETIRE_PER_PROPOSAL = 3;

    private final DecisionDefinitionRepository definitions;
    private final DecisionDefinitionParser parser;
    private final DecisionRecordRepository decisionRecords;
    private final DecisionRuntime runtime;
    private final ProposalRepository proposals;
    private final LearnerGovernance governance;

    @Value("${oms.learning.df07.window-days:30}") private int windowDays;
    @Value("${oms.learning.df07.min-sample:100}") private int minSample;
    @Value("${oms.learning.df07.min-labelled:20}") private int minLabelled;
    @Value("${oms.learning.df07.replay-limit:50}") private int replayLimit;
    @Value("${oms.learning.df07.margin:0.05}") private double margin;
    /** How far back, before the observation window closed, matured fraud outcomes are read (DF-07 ground truth). */
    @Value("${oms.learning.df07.fraud-lookback-days:365}") private int fraudLookbackDays = 365;

    public RuleUtilizationLearner(DecisionDefinitionRepository definitions, DecisionDefinitionParser parser, DecisionRecordRepository decisionRecords,
                                  DecisionRuntime runtime, ProposalRepository proposals, LearnerGovernance governance) {
        this.definitions = definitions;
        this.parser = parser;
        this.decisionRecords = decisionRecords;
        this.runtime = runtime;
        this.proposals = proposals;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df07.sweep-ms:86400000}", initialDelayString = "${oms.learning.df07.sweep-ms:850000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Rule-utilization sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int n = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        if (!governance.begin(KEY).mayPropose()) return 0;
        governance.recordRun(KEY);
        int n = 0;
        for (String name : definitions.names()) {
            try { if (sweepDecision(name)) n++; } catch (RuntimeException e) { log.warn("Rule-utilization check failed for {}: {}", name, e.toString()); }
        }
        return n;
    }

    private boolean sweepDecision(String name) {
        var live = definitions.byStatus(name, "live");
        if (live.isEmpty()) return false;
        DecisionDefinition def = live.get(0).definition();
        if (def.rules().isEmpty() || proposals.existsOpenFor("decision:" + name)) return false;
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));

        List<DecisionRecord> recent = decisionRecords.recentLive(name, def.version(), since, Math.max(minSample, 1000));
        if (recent.size() >= minSample) {
            Set<String> fired = new HashSet<>();
            for (DecisionRecord r : recent) if (r.rulesFired() != null) fired.addAll(r.rulesFired());
            List<PolicyChange> retire = new ArrayList<>();
            for (RuleDef rule : def.rules()) {
                if (fired.contains(rule.id()) || retire.size() >= MAX_RETIRE_PER_PROPOSAL) continue;
                retire.add(new PolicyChange.RuleRetire(name, rule.id(), "fired 0 times in " + recent.size() + " live decisions over " + windowDays + " days"));
            }
            if (!retire.isEmpty()) {
                propose(def, retire, retire.size() + " rule(s) on " + name + " never fired across " + recent.size() + " live decisions in " + windowDays + " days");
                return true;
            }
        }

        List<Truth> known = groundTruth(def, since);
        for (RuleDef rule : def.rules()) {
            if (rule.blocks() || rule.escalates()) continue; // a rule that hands off or blocks does not answer the question
            List<Truth> decidedByRule = known.stream().filter(t -> t.record().rulesFired() != null && t.record().rulesFired().contains(rule.id())).limit(replayLimit).toList();
            if (decidedByRule.size() < minLabelled) continue;
            Replay replay = replayWithout(def, rule, decidedByRule);
            if (replay == null) continue;
            log.info("DF-07 {} rule {}: right on {}% of {} cases with a known answer ({}), the model without it on {}%", name, rule.id(),
                Math.round(replay.ruleAgreement() * 100), replay.cases(), replay.sources(), Math.round(replay.l1Agreement() * 100));
            if (replay.l1Agreement() < replay.ruleAgreement() + margin) continue;
            String evidence = String.format(Locale.ROOT, "Without rule %s the model is right on %.0f%% of %d cases whose answer is known (%s), the rule on %.0f%%",
                rule.id(), replay.l1Agreement() * 100, replay.cases(), replay.sources(), replay.ruleAgreement() * 100);
            if (decidedByRule.stream().allMatch(t -> t.fraud() == null)) {
                evidence = String.format(Locale.ROOT, "Without rule %s the model agrees with people on %.0f%% of %d resolved cases, the rule on %.0f%%",
                    rule.id(), replay.l1Agreement() * 100, replay.cases(), replay.ruleAgreement() * 100);
            }
            propose(def, List.of(new PolicyChange.RuleRetire(name, rule.id(), evidence)), evidence);
            return true;
        }
        return false;
    }

    /** A decided record whose right answer is known: from its fraud outcome ({@code fraud} set) or a person's resolution. */
    record Truth(DecisionRecord record, Boolean fraud, String human) {
        boolean right(Object answer) {
            if (fraud != null) return answersRisky(answer) == fraud;
            return human != null && human.equalsIgnoreCase(String.valueOf(answer));
        }
    }

    /**
     * Every record of the decision with a known answer: matured fraud outcomes (the decision's own observation window,
     * cohort-sampled) and, for the rest, people's resolutions in the learner's window. A record counts once, outcome first.
     */
    List<Truth> groundTruth(DecisionDefinition def, Instant since) {
        Instant now = Instant.now();
        java.util.Map<String, Truth> out = new java.util.LinkedHashMap<>();
        { // any decision whose records carry fraud outcomes (in practice the fraud decisions)
            int window = config == null ? FraudLabels.DEFAULT_WINDOW_DAYS : FraudLabels.windowDays(config, def.name());
            Instant cohortEnd = FraudLabels.cohortEnd(now, window);
            Instant cohortStart = cohortEnd.minus(Duration.ofDays(Math.max(1, fraudLookbackDays)));
            for (int[] v : decisionRecords.activeVersions(def.name(), cohortStart)) {
                for (DecisionRecord r : decisionRecords.labelledBetween(def.name(), v[0], cohortStart, cohortEnd.plusMillis(1))) {
                    Boolean fraud = FraudLabels.sampled(r, now, window);
                    if (fraud != null) out.put(r.id(), new Truth(r, fraud, null));
                }
            }
        }
        for (DecisionRecord r : decisionRecords.labelledBetween(def.name(), def.version(), since, now)) {
            if (out.containsKey(r.id())) continue;
            ai.intentoms.learning.model.HumanResolution.value(r).ifPresent(h -> out.put(r.id(), new Truth(r, null, h)));
        }
        return List.copyOf(out.values());
    }

    /** Whether an answer calls the case risky: yes on a yes/no, the upper half of a 0-4 score, a block/hold/deny word. */
    static boolean answersRisky(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() >= 2.0;
        String s = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (s.matches("-?\\d+(\\.\\d+)?")) return Double.parseDouble(s) >= 2.0;
        return s.equals("true") || s.equals("block") || s.equals("decline") || s.equals("hold") || s.equals("high") || s.equals("review") || s.startsWith("deny");
    }

    record Replay(int cases, double ruleAgreement, double l1Agreement, String sources) {}

    private Replay replayWithout(DecisionDefinition def, RuleDef rule, List<Truth> cases) {
        DecisionDefinition candidate;
        try { candidate = parser.parse(ChangesetApplier.applyToDecision(def.yaml(), List.of(new PolicyChange.RuleRetire(def.name(), rule.id(), null)))); }
        catch (RuntimeException e) { log.warn("Could not build {} without rule {}: {}", def.name(), rule.id(), e.toString()); return null; }
        int ruleAgree = 0, l1Agree = 0;
        for (Truth t : cases) {
            DecisionRecord r = t.record();
            if (t.right(r.value())) ruleAgree++;
            // The model's answer without the rule: a simulation run of the recorded input as the system principal.
            DecisionResult sim = SystemPrincipal.call(() -> runtime.evaluate(candidate, r.input(),
                new DecisionOptions(r.subjectType(), r.subjectId(), r.correlationId(), Mode.SIMULATION, def.version(), Actor.system(KEY), Map.of())));
            if (t.right(sim.value())) l1Agree++;
        }
        long outcomes = cases.stream().filter(t -> t.fraud() != null).count();
        String sources = (outcomes > 0 ? outcomes + " fraud outcomes" : "") + (outcomes > 0 && outcomes < cases.size() ? ", " : "")
            + (outcomes < cases.size() ? (cases.size() - outcomes) + " human resolutions" : "");
        return new Replay(cases.size(), ruleAgree / (double) cases.size(), l1Agree / (double) cases.size(), sources);
    }


    private ai.intentoms.platform.config.TenantConfigStore config;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConfig(ai.intentoms.platform.config.TenantConfigStore config) { this.config = config; }

    private void propose(DecisionDefinition def, List<PolicyChange> changes, String motivation) {
        PolicyChangeset.RiskTier tier = PolicyChangeset.RiskTier.valueOf(def.tier().name());
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", def.name()), def.version(), changes, motivation, tier);
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, null, tier, ProposalStatus.DRAFT, null, null, Actor.system(KEY).ref(),
            Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        log.info("DF-07 proposal {}: {}", p.proposalId(), motivation);
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Unused rule finder", "Finds rules that never fire, or that the model now beats, and suggests retiring them.", LearnerDescriptor.Loop.FAST, "DF-07");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
