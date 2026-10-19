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
 *
 * <p><b>Generalising</b> (audit C-N3): situations people overrode to the same answer are merged when the cases support
 * it: the merged condition keeps only what the cases agree on (equal text and flags, the range of numbers that vary,
 * {@link SituationKey#when}), and the merge is kept only while every person-resolved case that condition would match
 * still agrees on the answer (at least {@code min-agreement}) and mostly overrode the model. Identical cases therefore
 * still give an exact rule, and differing ones give the rule their shared fields describe. The {@code min-cases}
 * count applies to the cases the condition matches.
 *
 * <p>Its candidates are its own: the key is {@code rule:<decision>:<condition>} and only earlier {@code add_rule}
 * hypotheses block a new one, so a golden-set candidate for the same situation (DF-03) no longer starves it.
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
            for (List<DecisionRecord> group : generalisedGroups(resolved, def.stateInclude())) {
                if (group.size() < minCases) continue;
                if (raiseIfAgreedAndNew(def, group)) n++;
            }
        }
        return n;
    }

    /**
     * Situations people resolved, merged where the cases support it (see the class comment). Each result is the set of
     * person-resolved cases a candidate condition matches.
     */
    List<List<DecisionRecord>> generalisedGroups(List<DecisionRecord> resolved, List<String> includes) {
        Map<String, List<DecisionRecord>> bySituation = resolved.stream()
            .collect(Collectors.groupingBy(r -> SituationKey.key(r.input(), includes), java.util.LinkedHashMap::new, Collectors.toList()));
        // Seeds: situations whose own resolutions agree and mostly override; biggest first.
        List<List<DecisionRecord>> seeds = bySituation.values().stream()
            .filter(g -> { Agreement a = agreement(g); return a.top() != null && a.rate() >= minAgreement && a.overrides() * 2 >= a.resolutions(); })
            .sorted((x, y) -> Integer.compare(y.size(), x.size())).toList();
        List<List<DecisionRecord>> out = new java.util.ArrayList<>();
        java.util.Set<List<DecisionRecord>> used = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<DecisionRecord> seed : seeds) {
            if (used.contains(seed)) continue;
            used.add(seed);
            String answer = agreement(seed).top();
            List<DecisionRecord> agreeing = new java.util.ArrayList<>(seed.stream().filter(r -> answer.equals(action(r))).toList());
            List<DecisionRecord> matched = seed;
            for (List<DecisionRecord> other : seeds) {
                if (used.contains(other) || !answer.equals(agreement(other).top())) continue;
                List<DecisionRecord> trial = new java.util.ArrayList<>(agreeing);
                other.stream().filter(r -> answer.equals(action(r))).forEach(trial::add);
                Condition c = Condition.of(trial.stream().map(DecisionRecord::input).toList(), includes);
                if (c.isEmpty()) continue; // a rule must say when it applies
                List<DecisionRecord> m = resolved.stream().filter(r -> c.matches(r.input(), includes)).toList();
                Agreement a = agreement(m);
                if (!answer.equals(a.top()) || a.rate() < minAgreement || a.overrides() * 2 < a.resolutions()) continue;
                agreeing = trial;
                matched = m;
                used.add(other);
            }
            out.add(matched);
        }
        return out;
    }

    /**
     * What a set of cases agrees on, as a matcher for the condition {@link SituationKey#when} writes: equal text and flags
     * on the leaves every case shares, and the range of numbers.
     */
    record Condition(Map<String, Object> equal, Map<String, double[]> range) {
        static Condition of(List<Map<String, Object>> inputs, List<String> includes) {
            Map<String, Object> equal = new java.util.TreeMap<>();
            Map<String, double[]> range = new java.util.TreeMap<>();
            if (inputs.isEmpty()) return new Condition(equal, range);
            List<Map<String, Object>> all = inputs.stream().map(i -> SituationKey.leaves(i, includes)).toList();
            for (Map.Entry<String, Object> e : all.get(0).entrySet()) {
                String path = e.getKey();
                if (!all.stream().allMatch(m -> m.containsKey(path))) continue;
                List<Object> values = all.stream().map(m -> m.get(path)).toList();
                if (values.stream().allMatch(v -> v instanceof Number)) {
                    range.put(path, new double[]{values.stream().mapToDouble(v -> ((Number) v).doubleValue()).min().orElse(0),
                        values.stream().mapToDouble(v -> ((Number) v).doubleValue()).max().orElse(0)});
                } else if (values.stream().distinct().count() == 1) {
                    Object v = values.get(0);
                    if (v instanceof Boolean || (v instanceof String str && !str.startsWith("["))) equal.put(path, v);
                }
            }
            return new Condition(equal, range);
        }

        boolean isEmpty() { return equal.isEmpty() && range.isEmpty(); }

        boolean matches(Map<String, Object> input, List<String> includes) {
            Map<String, Object> leaves = SituationKey.leaves(input, includes);
            for (var e : equal.entrySet()) if (!e.getValue().equals(leaves.get(e.getKey()))) return false;
            for (var e : range.entrySet()) {
                if (!(leaves.get(e.getKey()) instanceof Number n)) return false;
                double d = n.doubleValue();
                if (d < e.getValue()[0] - 1e-9 || d > e.getValue()[1] + 1e-9) return false;
            }
            return true;
        }
    }

    /** The value the person chose (OC-06), never the task's outcome word. */
    static String action(DecisionRecord r) {
        return ai.intentoms.learning.model.HumanResolution.value(r).orElse(null);
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

    /** Key prefix of this learner's candidates (DF-03 uses its own), so the two never block each other. */
    static final String GROUP_PREFIX = "rule:";

    private boolean raiseIfAgreedAndNew(DecisionDefinition def, List<DecisionRecord> group) {
        String decisionName = def.name();
        Agreement a = agreement(group);
        if (a.top() == null || a.rate() < minAgreement) return false;
        if (a.overrides() * 2 < a.resolutions()) return false; // people mostly confirm the model: no rule needed

        List<DecisionRecord> agreeing = group.stream().filter(r -> a.top().equals(action(r))).toList();
        String when = SituationKey.when(agreeing.stream().map(DecisionRecord::input).toList(), def.stateInclude());
        String situation = ai.intentoms.platform.json.Json.sha256(Map.of("when", when, "then", a.top()));
        String refKey = GROUP_PREFIX + decisionName + ":" + situation;
        boolean already = java.util.stream.Stream.of("OPEN", "DRAFTED", "ACCEPTED").flatMap(st -> hypotheses.byStatus(st, 500).stream())
            .anyMatch(h -> "add_rule".equals(h.lever().get("action")) && refKey.equals(h.lever().get("groupKey")));
        if (already) return false;

        Map<String, Object> lever = new java.util.LinkedHashMap<>();
        lever.put("action", "add_rule");
        lever.put("decision", decisionName);
        lever.put("groupKey", refKey);
        lever.put("inputHash", situation);
        lever.put("situations", group.stream().map(r -> SituationKey.key(r.input(), def.stateInclude())).distinct().count());
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
