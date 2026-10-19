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
 *   <li><b>L1 outperforms it on replay:</b> for a rule that does fire, the decisions it decided that a person later
 *       resolved are replayed without the rule (an in-memory candidate built from the live YAML, evaluated in simulation
 *       mode as the system principal). If the model alone agrees with the human resolutions at least five points more
 *       often than the rule did, retiring it is proposed with that evidence.</li>
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

        List<DecisionRecord> labelled = decisionRecords.labelledBetween(name, def.version(), since, Instant.now()).stream()
            .filter(ai.intentoms.learning.model.HumanResolution::hasValue).toList();
        for (RuleDef rule : def.rules()) {
            List<DecisionRecord> decidedByRule = labelled.stream().filter(r -> r.rulesFired() != null && r.rulesFired().contains(rule.id())).limit(replayLimit).toList();
            if (decidedByRule.size() < minLabelled) continue;
            Replay replay = replayWithout(def, rule, decidedByRule);
            if (replay == null || replay.l1Agreement() < replay.ruleAgreement() + margin) continue;
            String evidence = String.format(Locale.ROOT, "Without rule %s the model agrees with people on %.0f%% of %d resolved cases, the rule on %.0f%%",
                rule.id(), replay.l1Agreement() * 100, replay.cases(), replay.ruleAgreement() * 100);
            propose(def, List.of(new PolicyChange.RuleRetire(name, rule.id(), evidence)), evidence);
            return true;
        }
        return false;
    }

    record Replay(int cases, double ruleAgreement, double l1Agreement) {}

    private Replay replayWithout(DecisionDefinition def, RuleDef rule, List<DecisionRecord> cases) {
        DecisionDefinition candidate;
        try { candidate = parser.parse(ChangesetApplier.applyToDecision(def.yaml(), List.of(new PolicyChange.RuleRetire(def.name(), rule.id(), null)))); }
        catch (RuntimeException e) { log.warn("Could not build {} without rule {}: {}", def.name(), rule.id(), e.toString()); return null; }
        int ruleAgree = 0, l1Agree = 0;
        for (DecisionRecord r : cases) {
            String human = ai.intentoms.learning.model.HumanResolution.value(r).orElse("");
            if (human.equalsIgnoreCase(String.valueOf(r.value()))) ruleAgree++;
            DecisionResult sim = SystemPrincipal.call(() -> runtime.evaluate(candidate, r.input(),
                new DecisionOptions(r.subjectType(), r.subjectId(), r.correlationId(), Mode.SIMULATION, def.version(), Actor.system(KEY), Map.of())));
            if (human.equalsIgnoreCase(String.valueOf(sim.value()))) l1Agree++;
        }
        return new Replay(cases.size(), ruleAgree / (double) cases.size(), l1Agree / (double) cases.size());
    }

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
