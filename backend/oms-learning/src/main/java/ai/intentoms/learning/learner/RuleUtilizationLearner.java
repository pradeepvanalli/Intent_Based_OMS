package ai.intentoms.learning.learner;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DF-07: proposes retiring L0 rules that never fire — tallies {@code rules_fired} across recent live decision
 * records (via the existing {@code recentLive} read, no new repository method needed) and flags any rule in the
 * definition's own rule list with zero fires over the window. Raises a {@link Hypothesis}, not a {@code Proposal}:
 * turning this into an actual {@code RuleRetire} change needs a draft version to run an impact-diff against
 * ({@code DecisionStudioService.impactDiff}), and creating that draft requires a human principal
 * ({@code createDraft} is gated by {@code requireHuman()}) — so a human (or the author-LLM pipeline once it
 * exists) has to open the draft first. The other half of DF-07, "or that L1 now outperforms on replay," needs that
 * same human-opened draft to diff against and isn't attempted here for the same reason.
 *
 * <p>DF-06 (prompt versions gated like decision versions) is out of scope for this learner entirely — it depends
 * on the Analyst/Author LLM pipeline a different build extends from oms-copilot. The extension point is
 * {@code DecisionStudioService}'s existing lint -&gt; evaluate -&gt; approve -&gt; publish gate (see
 * {@code DecisionStudioService.java}): a prompt version should become a versioned artifact that passes through the
 * identical sequence, most naturally as a new {@code PolicyChange} variant once the author pipeline exists to
 * produce one.
 */
@Component
public class RuleUtilizationLearner {
    private static final Logger log = LoggerFactory.getLogger(RuleUtilizationLearner.class);

    private final DecisionDefinitionRepository definitions;
    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;

    @Value("${oms.learning.df07.window-days:30}") private int windowDays;
    @Value("${oms.learning.df07.min-sample:100}") private int minSample;

    public RuleUtilizationLearner(DecisionDefinitionRepository definitions, DecisionRecordRepository decisionRecords, HypothesisRepository hypotheses) {
        this.definitions = definitions;
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
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
        if (def.rules().isEmpty()) return false;

        List<DecisionRecord> recent = decisionRecords.recentLive(name, def.version(), Instant.now().minus(Duration.ofDays(windowDays)), minSample);
        if (recent.size() < minSample) return false; // not enough traffic yet to trust a zero-fire count

        Set<String> fired = new HashSet<>();
        for (DecisionRecord r : recent) if (r.rulesFired() != null) fired.addAll(r.rulesFired());

        int raised = 0;
        for (RuleDef rule : def.rules()) {
            if (fired.contains(rule.id())) continue;
            if (alreadyRaised(name, rule.id())) continue;
            Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", name), "rule_utilization", Direction.DECREASE,
                Map.of("action", "retire_rule", "decision", name, "ruleId", rule.id(), "groupKey", name + ":" + rule.id()),
                0.6, List.of(), "Rule \"" + rule.id() + "\" on " + name + " fired 0 times across " + recent.size() + " live decisions in the last " + windowDays + " days.",
                Hypothesis.Status.OPEN, Instant.now());
            hypotheses.insert(h);
            log.info("DF-07 hypothesis {}: rule {} on {} never fired in {} samples", h.hypothesisId(), rule.id(), name, recent.size());
            raised++;
        }
        return raised > 0;
    }

    private boolean alreadyRaised(String decisionName, String ruleId) {
        String key = decisionName + ":" + ruleId;
        return hypotheses.byStatus("OPEN", 500).stream().anyMatch(h -> key.equals(h.lever().get("groupKey")))
            || hypotheses.byStatus("DRAFTED", 500).stream().anyMatch(h -> key.equals(h.lever().get("groupKey")));
    }
}
