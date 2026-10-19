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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DF-03: grows golden sets from human-labeled escalations after deduplication and reviewer sign-off. Groups
 * {@code decision_record} rows carrying a {@code human_resolution} label (written by {@code HumanResolutionCapture})
 * by the normalised situation ({@link SituationKey}: what the decision reads, identifiers dropped) and raises a
 * {@link Hypothesis} when a situation recurred at least {@code oms.learning.df03.min-occurrences} times (default 3)
 * with at least {@code oms.learning.df03.min-agreement} (default 90%) of people giving the same answer, and only when
 * <ul>
 *   <li>the golden set does not already hold that situation (deduplication against existing cases), and</li>
 *   <li>no live L0 rule of the decision fires on it with a different answer (a golden case must not contradict a rule
 *       that would decide the case before the model is asked).</li>
 * </ul>
 * Open candidates that no longer pass (a case was added, a rule changed, or a candidate came from elsewhere) are
 * rejected on the next sweep. Does not insert into {@code decision_golden_case} itself: that still needs a reviewer's
 * sign-off.
 */
@Component
public class GoldenSetGrowthLearner implements GovernedLearner {
    public static final String KEY = "golden_set_growth";
    private static final Logger log = LoggerFactory.getLogger(GoldenSetGrowthLearner.class);
    private static final String HUMAN_RESOLUTION_LABEL = "human_resolution";

    private final DecisionDefinitionRepository definitions;
    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;
    private final ai.intentoms.decision.definition.GoldenCaseRepository golden;
    private final ai.intentoms.decision.rules.RuleEngine rules;
    private final ai.intentoms.platform.config.TenantConfigStore config;

    @Value("${oms.learning.df03.min-occurrences:3}") private int minOccurrences;
    @Value("${oms.learning.df03.min-agreement:0.9}") private double minAgreement = 0.9;
    @Value("${oms.learning.df03.window-days:60}") private int windowDays;

    private final LearnerGovernance governance;

    public GoldenSetGrowthLearner(DecisionDefinitionRepository definitions, DecisionRecordRepository decisionRecords, HypothesisRepository hypotheses, LearnerGovernance governance,
                                  ai.intentoms.decision.definition.GoldenCaseRepository golden, ai.intentoms.decision.rules.RuleEngine rules,
                                  ai.intentoms.platform.config.TenantConfigStore config) {
        this.governance = governance;
        this.golden = golden;
        this.rules = rules;
        this.config = config;
        this.definitions = definitions;
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df03.sweep-ms:86400000}", initialDelayString = "${oms.learning.df03.sweep-ms:700000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Golden-set growth sweep failed: {}", e.toString()); }
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
            if (def.evaluation() == null) continue; // no golden set to grow
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (var c : golden.forSet(def.evaluation().goldenSet())) existing.add(SituationKey.key(c.input(), def.stateInclude()));
            revalidateOpen(name, def, existing);
            List<DecisionRecord> labelled = decisionRecords.labelledBetween(name, def.version(), since, Instant.now()).stream()
                .filter(r -> r.labels() != null && r.labels().containsKey(HUMAN_RESOLUTION_LABEL) && action(r) != null).toList();
            Map<String, List<DecisionRecord>> bySituation = labelled.stream().collect(Collectors.groupingBy(r -> SituationKey.key(r.input(), def.stateInclude())));
            for (var entry : bySituation.entrySet()) {
                if (entry.getValue().size() < minOccurrences) continue;
                if (existing.contains(entry.getKey())) continue; // the golden set already covers this situation
                if (raiseIfNew(def, entry.getKey(), entry.getValue())) n++;
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

    /** The answer of a live L0 rule that decides this input before the model is asked, if any (blocks and escalations are not answers). */
    Optional<ai.intentoms.decision.model.RuleDef> contradictingRule(DecisionDefinition def, Map<String, Object> input, String expected) {
        if (def.rules().isEmpty() || input == null) return Optional.empty();
        var vars = ai.intentoms.decision.rules.CelVars.base(input, config.celTenantMap(), Map.of());
        return rules.firstMatch(def.rules(), vars).map(ai.intentoms.decision.rules.RuleEngine.Match::rule)
            .filter(r -> !r.blocks() && !r.escalates() && !String.valueOf(r.then()).equalsIgnoreCase(expected));
    }

    private boolean raiseIfNew(DecisionDefinition def, String situation, List<DecisionRecord> group) {
        String decisionName = def.name();
        String refKey = decisionName + ":" + situation;
        boolean already = hypotheses.byStatus("OPEN", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")))
            || hypotheses.byStatus("DRAFTED", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")));
        if (already) return false;

        Map<String, Long> tally = group.stream().collect(Collectors.groupingBy(GoldenSetGrowthLearner::action, Collectors.counting()));
        var top = tally.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        double agreement = top.getValue() / (double) group.size();
        if (agreement < minAgreement) {
            log.info("DF-03 {}: {} resolutions of one situation, only {}% agree; not a golden case", decisionName, group.size(), Math.round(agreement * 100));
            return false;
        }
        String action = top.getKey();
        List<DecisionRecord> agreeing = group.stream().filter(r -> action.equals(action(r))).toList();
        Optional<ai.intentoms.decision.model.RuleDef> rule = contradictingRule(def, agreeing.get(0).input(), action);
        if (rule.isPresent()) {
            log.info("DF-03 {}: people answer \"{}\" but live rule {} answers \"{}\"; not a golden case", decisionName, action, rule.get().id(), rule.get().then());
            return false;
        }

        Map<String, Object> lever = new java.util.LinkedHashMap<>();
        lever.put("action", "add_golden_case");
        lever.put("decision", decisionName);
        lever.put("groupKey", refKey);
        lever.put("inputHash", situation);
        lever.put("expectedValue", action);
        lever.put("resolutions", group.size());
        lever.put("agreement", Math.round(agreement * 1000) / 1000.0);
        lever.put("sourceRecordIds", agreeing.stream().map(DecisionRecord::id).limit(10).toList());
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decisionName), "golden_set_coverage", Direction.INCREASE, lever,
            Math.min(0.95, agreement), agreeing.stream().map(DecisionRecord::id).toList(),
            group.size() + " human resolutions of the same situation for " + decisionName + ", " + Math.round(agreement * 100) + "% agreed on \"" + action
                + "\"; not yet in the golden set and no live rule disagrees.",
            Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        log.info("DF-03 hypothesis {}: {} resolutions ({}% agree) for {} (situation {})", h.hypothesisId(), group.size(), Math.round(agreement * 100), decisionName, situation);
        return true;
    }

    /** Rejects open candidates that are now duplicates of a golden case or contradicted by a live rule. */
    private void revalidateOpen(String decisionName, DecisionDefinition def, java.util.Set<String> existing) {
        for (Hypothesis h : hypotheses.byStatus("OPEN", 500)) {
            if (!"add_golden_case".equals(h.lever().get("action")) || !decisionName.equals(h.lever().get("decision"))) continue;
            Object ids = h.lever().get("sourceRecordIds");
            List<?> sources = ids instanceof List<?> l ? l : h.evidence();
            Optional<DecisionRecord> source = sources == null ? Optional.empty()
                : sources.stream().map(id -> decisionRecords.find(String.valueOf(id))).flatMap(Optional::stream).filter(r -> r.input() != null).findFirst();
            if (source.isEmpty()) continue;
            String expected = String.valueOf(h.lever().get("expectedValue"));
            boolean duplicate = existing.contains(SituationKey.key(source.get().input(), def.stateInclude()));
            Optional<ai.intentoms.decision.model.RuleDef> rule = contradictingRule(def, source.get().input(), expected);
            if (duplicate || rule.isPresent()) {
                hypotheses.updateStatus(h.hypothesisId(), Hypothesis.Status.REJECTED);
                log.info("DF-03 candidate {} rejected: {}", h.hypothesisId(), duplicate ? "the golden set already has this situation"
                    : "live rule " + rule.get().id() + " answers \"" + rule.get().then() + "\", not \"" + expected + "\"");
            }
        }
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Test case growth", "Turns situations people keep resolving the same way into new test cases, after a reviewer signs off.", LearnerDescriptor.Loop.FAST, "DF-03");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
