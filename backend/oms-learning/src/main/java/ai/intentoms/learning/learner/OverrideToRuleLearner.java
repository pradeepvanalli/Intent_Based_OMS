package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DF-04: override-to-rule promotion. When humans resolve the same input the same way often enough (default ≥20
 * cases, ≥90% agreement), raises a {@link Hypothesis} for the author LLM pipeline (a different build, extending
 * oms-copilot) to turn into an actual {@code RuleAdd}/{@code AllowListChange} proposal — this learner only detects
 * the pattern and produces evidence, it never drafts or applies a rule itself. Grouping is by exact input hash
 * (conservative; a looser similarity key like "address-reuse ring" needs feature-level clustering, out of scope
 * here) and only counts a resolution as a genuine override when it differs from the decision's own recommended
 * value.
 */
@Component
public class OverrideToRuleLearner implements GovernedLearner {
    public static final String KEY = "override_to_rule";
    private static final Logger log = LoggerFactory.getLogger(OverrideToRuleLearner.class);
    private static final String HUMAN_RESOLUTION_LABEL = "human_resolution";

    private final DecisionDefinitionRepository definitions;
    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;

    @Value("${oms.learning.df04.min-cases:20}") private int minCases;
    @Value("${oms.learning.df04.min-agreement:0.9}") private double minAgreement;
    @Value("${oms.learning.df04.window-days:90}") private int windowDays;

    private final LearnerGovernance governance;

    public OverrideToRuleLearner(DecisionDefinitionRepository definitions, DecisionRecordRepository decisionRecords, HypothesisRepository hypotheses, LearnerGovernance governance) {
        this.governance = governance;
        this.definitions = definitions;
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df04.sweep-ms:86400000}", initialDelayString = "${oms.learning.df04.sweep-ms:750000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Override-to-rule sweep failed: {}", e.toString()); }
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
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        for (String name : definitions.names()) {
            var live = definitions.byStatus(name, "live");
            if (live.isEmpty()) continue;
            DecisionDefinition def = live.get(0).definition();
            List<DecisionRecord> overrides = decisionRecords.labelledBetween(name, def.version(), since, Instant.now()).stream()
                .filter(r -> r.labels() != null && r.labels().containsKey(HUMAN_RESOLUTION_LABEL))
                .filter(this::isOverride)
                .toList();
            Map<String, List<DecisionRecord>> byInput = overrides.stream().collect(Collectors.groupingBy(DecisionRecord::inputHash));
            for (var entry : byInput.entrySet()) {
                if (entry.getValue().size() < minCases) continue;
                if (raiseIfAgreedAndNew(name, entry.getKey(), entry.getValue())) n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private boolean isOverride(DecisionRecord r) {
        Map<String, Object> resolution = (Map<String, Object>) r.labels().get(HUMAN_RESOLUTION_LABEL);
        Object action = resolution == null ? null : resolution.get("action");
        return action != null && !String.valueOf(action).isBlank() && !String.valueOf(action).equalsIgnoreCase(String.valueOf(r.effectiveValue()));
    }

    @SuppressWarnings("unchecked")
    private boolean raiseIfAgreedAndNew(String decisionName, String inputHash, List<DecisionRecord> group) {
        Map<String, Long> tally = group.stream().collect(Collectors.groupingBy(
            r -> String.valueOf(((Map<String, Object>) r.labels().get(HUMAN_RESOLUTION_LABEL)).get("action")), Collectors.counting()));
        String top = tally.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (top == null) return false;
        double agreement = tally.get(top) / (double) group.size();
        if (agreement < minAgreement) return false;

        String refKey = decisionName + ":" + inputHash;
        boolean already = hypotheses.byStatus("OPEN", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")))
            || hypotheses.byStatus("DRAFTED", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")));
        if (already) return false;

        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decisionName), "override_rate", Direction.DECREASE,
            Map.of("action", "add_rule", "decision", decisionName, "groupKey", refKey, "inputHash", inputHash, "suggestedThen", top),
            Math.min(0.95, agreement), group.stream().map(DecisionRecord::id).limit(25).toList(),
            group.size() + " human overrides for the same input (" + decisionName + "), " + Math.round(agreement * 100) + "% agreed on \"" + top + "\" — candidate for an L0 rule.",
            Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        log.info("DF-04 hypothesis {}: {} overrides, {}% agreement on \"{}\" for {}", h.hypothesisId(), group.size(), Math.round(agreement * 100), top, decisionName);
        return true;
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Override-to-rule finder", "Spots cases people override the same way again and again and suggests a rule for them.", LearnerDescriptor.Loop.FAST, "DF-04");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
