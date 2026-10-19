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
 * DF-04: override-to-rule promotion. When humans resolve the same situation the same way often enough (default
 * ≥20 cases, ≥90% agreement), raises a {@link Hypothesis} for the author (LLM, or the template author without a key)
 * to turn into a {@code RuleAdd}/{@code AllowListChange} proposal; this learner only detects the pattern and produces
 * evidence, with a condition ({@code when}) that matches every case in the group. The situation is the normalised
 * {@link SituationKey} of what the decision reads, not the raw input hash. Agreement is measured over <i>every</i>
 * human resolution of the situation, including people who confirmed the model (defect 22: counting only overrides
 * hid the dissent); a rule is only worth drafting when most of those resolutions overrode the model.
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
            List<DecisionRecord> resolved = decisionRecords.labelledBetween(name, def.version(), since, Instant.now()).stream()
                .filter(r -> r.labels() != null && r.labels().containsKey(HUMAN_RESOLUTION_LABEL) && action(r) != null)
                .toList();
            Map<String, List<DecisionRecord>> bySituation = resolved.stream().collect(Collectors.groupingBy(r -> SituationKey.key(r.input(), def.stateInclude())));
            for (var entry : bySituation.entrySet()) {
                if (entry.getValue().size() < minCases) continue;
                if (raiseIfAgreedAndNew(def, entry.getKey(), entry.getValue())) n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    static String action(DecisionRecord r) {
        Object res = r.labels() == null ? null : r.labels().get(HUMAN_RESOLUTION_LABEL);
        Object action = res instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("action") : null;
        return action == null || String.valueOf(action).isBlank() ? null : String.valueOf(action);
    }

    static boolean isOverride(DecisionRecord r) {
        String action = action(r);
        return action != null && !action.equalsIgnoreCase(String.valueOf(r.value()));
    }

    /** The share of the group's resolutions that gave the most common answer, and how many of those overrode the model. */
    public record Agreement(String top, int resolutions, long agreeing, long overrides) {
        public double rate() { return resolutions == 0 ? 0 : agreeing / (double) resolutions; }
    }

    static Agreement agreement(List<DecisionRecord> group) {
        Map<String, Long> tally = group.stream().collect(Collectors.groupingBy(OverrideToRuleLearner::action, Collectors.counting()));
        var top = tally.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (top == null) return new Agreement(null, group.size(), 0, 0);
        long overrides = group.stream().filter(r -> top.getKey().equals(action(r)) && isOverride(r)).count();
        return new Agreement(top.getKey(), group.size(), top.getValue(), overrides);
    }

    private boolean raiseIfAgreedAndNew(DecisionDefinition def, String situation, List<DecisionRecord> group) {
        String decisionName = def.name();
        Agreement a = agreement(group);
        if (a.top() == null || a.rate() < minAgreement) return false;
        if (a.overrides() * 2 < a.resolutions()) return false; // people mostly confirm the model: no rule needed

        String refKey = decisionName + ":" + situation;
        boolean already = hypotheses.byStatus("OPEN", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")))
            || hypotheses.byStatus("DRAFTED", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")));
        if (already) return false;

        List<DecisionRecord> agreeing = group.stream().filter(r -> a.top().equals(action(r))).toList();
        String when = SituationKey.when(agreeing.stream().map(DecisionRecord::input).toList(), def.stateInclude());
        Map<String, Object> lever = new java.util.LinkedHashMap<>();
        lever.put("action", "add_rule");
        lever.put("decision", decisionName);
        lever.put("groupKey", refKey);
        lever.put("inputHash", situation);
        lever.put("suggestedThen", a.top());
        lever.put("when", when);
        lever.put("resolutions", a.resolutions());
        lever.put("agreeing", a.agreeing());
        lever.put("overrides", a.overrides());
        lever.put("agreement", Math.round(a.rate() * 1000) / 1000.0);
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decisionName), "override_rate", Direction.DECREASE, lever,
            Math.min(0.95, a.rate()), agreeing.stream().map(DecisionRecord::id).limit(25).toList(),
            a.resolutions() + " human resolutions of the same situation (" + decisionName + "), " + Math.round(a.rate() * 100) + "% agreed on \"" + a.top() + "\" ("
                + a.overrides() + " overriding the model) - candidate for an L0 rule when " + when + ".",
            Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        log.info("DF-04 hypothesis {}: {} resolutions, {}% agreement on \"{}\" ({} overrides) for {}", h.hypothesisId(), a.resolutions(), Math.round(a.rate() * 100),
            a.top(), a.overrides(), decisionName);
        return true;
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Override-to-rule finder", "Spots cases people override the same way again and again and suggests a rule for them.", LearnerDescriptor.Loop.FAST, "DF-04");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
