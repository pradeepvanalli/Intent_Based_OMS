package ai.intentoms.decision.studio;

import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.LintReport;
import ai.intentoms.decision.model.LintReport.LintIssue;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content lint for the studio (FR-PRM-05, FR-PRM-08, GR-16): overlapping option descriptions (Jaccard > 0.6), missing
 * escape option, vague rubric levels (< 3 words), PII words in prompts, text that contradicts an L0 rule, state include
 * paths outside the tenant's known schema (warning) and protected attributes in state includes (error for tier T2+).
 */
@Component
public class DecisionLinter {
    public static final double OVERLAP_THRESHOLD = 0.6;
    private static final Pattern PII_WORDS = Pattern.compile("(?i)\\b(e-?mail|phone|ssn|social security|date of birth|credit card|passport|driver'?s licen[cs]e)\\b");
    private static final Pattern PROTECTED = Pattern.compile("(?i)(^|[._])(race|ethnicity|gender|sex|religion|age|dob|date_of_birth|birth_date|national_origin|nationality|disability|marital_status|pregnan\\w*|sexual_orientation|citizenship|veteran)($|[._\\[])");
    private static final Pattern ZIP = Pattern.compile("(?i)(^|[._])(zip|zip_code|postal_code|postcode)($|[._\\[])");
    private static final Pattern QUOTED = Pattern.compile("['\"]([a-z][a-z0-9_]*)['\"]");
    private static final Pattern TOKEN = Pattern.compile("[^a-z0-9]+");

    private final DecisionDefinitionParser parser;
    private final CelEngine cel;
    private final Supplier<List<String>> stateSchema;

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionLinter(DecisionDefinitionParser parser, CelEngine cel, ai.intentoms.platform.config.TenantConfigStore config) {
        this(parser, cel, () -> TenantContext.current().isEmpty() ? List.of() : config.getList("decision.state_schema").stream().map(String::valueOf).toList());
    }

    /** Constructor for unit tests: the schema supplier replaces the tenant config lookup. */
    public DecisionLinter(DecisionDefinitionParser parser, CelEngine cel, Supplier<List<String>> stateSchema) {
        this.parser = parser;
        this.cel = cel;
        this.stateSchema = stateSchema;
    }

    public LintReport lint(String yaml) {
        DecisionDefinitionParser.Parsed parsed = parser.parseLenient(yaml);
        List<LintIssue> issues = new ArrayList<>(parsed.issues());
        if (parsed.definition() != null) issues.addAll(lintContent(parsed.definition()));
        return LintReport.of(issues);
    }

    public List<LintIssue> lintContent(DecisionDefinition def) {
        List<LintIssue> out = new ArrayList<>();
        QuestionDef main = def.main().orElse(null);
        for (QuestionDef q : def.questions().values()) {
            String ref = "questions." + q.key();
            if (q.isChoice()) {
                if (q.escape() == null) out.add(LintIssue.error("missing_escape_option", "Choice has no escape option (GR-03)", ref));
                List<String> keys = new ArrayList<>(q.choiceCriteria().keySet());
                for (int i = 0; i < keys.size(); i++) for (int j = i + 1; j < keys.size(); j++) {
                    double sim = jaccard(q.choiceCriteria().get(keys.get(i)), q.choiceCriteria().get(keys.get(j)));
                    if (sim > OVERLAP_THRESHOLD) out.add(LintIssue.warning("overlapping_options", String.format(Locale.ROOT, "Options '%s' and '%s' overlap (similarity %.2f)", keys.get(i), keys.get(j), sim), ref + ".criteria"));
                }
                for (var e : q.choiceCriteria().entrySet()) if (words(e.getValue()) < 2) out.add(LintIssue.warning("vague_option", "Option '" + e.getKey() + "' description is too short to discriminate", ref + ".criteria." + e.getKey()));
            }
            if (q.isScore()) for (int i = 0; i < q.levels(); i++) if (words(q.scoreLevels().get(i)) < 3) out.add(LintIssue.warning("vague_rubric_level", "Rubric level " + i + " has fewer than 3 words", ref + ".criteria[" + i + "]"));
            StringBuilder text = new StringBuilder();
            if (q.instructions() != null) text.append(q.instructions()).append(' ');
            if (q.statement() != null) text.append(q.statement()).append(' ');
            q.choiceCriteria().values().forEach(v -> text.append(v).append(' '));
            q.scoreLevels().forEach(v -> text.append(v).append(' '));
            Matcher pm = PII_WORDS.matcher(text);
            if (pm.find()) out.add(LintIssue.warning("pii_in_prompt", "Prompt text mentions PII ('" + pm.group(1) + "'); state is tokenised so the model cannot see it (GR-09)", ref));
        }
        // CEL validity of rules and checks (FR-DEC-03, GR-01).
        int i = 0;
        for (RuleDef r : def.rules()) {
            if (r.when() != null) for (String err : cel.validate(r.when())) out.add(LintIssue.error("cel_invalid", "Rule '" + r.id() + "' when: " + err, "rules[" + i + "].when"));
            if (main != null && main.isChoice() && r.then() != null && !r.escalates() && !r.blocks() && !main.choiceCriteria().containsKey(r.then())) {
                out.add(LintIssue.warning("rule_value_not_an_option", "Rule '" + r.id() + "' returns '" + r.then() + "', which is not an option of " + main.key() + "; downstream code must handle it", "rules[" + i + "].then"));
            }
            if (main != null && main.isChoice() && r.blocks() && r.when() != null) {
                Matcher qm = QUOTED.matcher(r.when());
                while (qm.find()) if (main.choiceCriteria().containsKey(qm.group(1))) out.add(LintIssue.warning("option_blocked_by_rule", "Option '" + qm.group(1) + "' is referenced by blocking rule '" + r.id() + "'; its description may contradict the rule", "rules[" + i + "]"));
            }
            i++;
        }
        for (int c = 0; c < def.preChecks().size(); c++) for (String err : cel.validate(def.preChecks().get(c))) out.add(LintIssue.error("cel_invalid", "preCheck: " + err, "preChecks[" + c + "]"));
        for (int c = 0; c < def.postChecks().size(); c++) for (String err : cel.validate(def.postChecks().get(c))) out.add(LintIssue.error("cel_invalid", "postCheck: " + err, "postChecks[" + c + "]"));

        // State includes: known schema (FR-PRM-05) and protected attributes (GR-16).
        List<String> schema = stateSchema.get();
        int p = 0;
        for (String path : def.stateInclude()) {
            String ref = "state.include[" + p++ + "]";
            String plain = path.replaceAll("\\[\\d*]", "");
            if (!schema.isEmpty() && schema.stream().noneMatch(s -> plain.equals(s) || plain.startsWith(s + ".") || s.startsWith(plain + "."))) {
                out.add(LintIssue.warning("unknown_state_path", "State path '" + path + "' is not in the tenant's known schema", ref));
            }
            if (PROTECTED.matcher(plain).find()) {
                if (def.tier() == Tier.T2 || def.tier() == Tier.T3) out.add(LintIssue.error("protected_attribute", "Protected attribute or close proxy '" + path + "' may not feed a tier " + def.tier() + " risk decision (GR-16)", ref));
                else out.add(LintIssue.warning("protected_attribute", "State path '" + path + "' looks like a protected attribute; review before use (GR-16)", ref));
            } else if (ZIP.matcher(plain).find() && (def.tier() == Tier.T2 || def.tier() == Tier.T3)) {
                out.add(LintIssue.warning("zip_proxy", "ZIP/postal code in a tier " + def.tier() + " decision can proxy protected attributes; justify in the change reason (GR-16)", ref));
            }
        }
        if (def.evaluation() == null && def.tier() != Tier.T0) out.add(LintIssue.warning("no_evaluation", "No evaluation golden set configured; publishing will not be gated on accuracy (GR-11)", "evaluation"));
        return out;
    }

    static double jaccard(String a, String b) {
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return inter.size() / (double) union.size();
    }

    static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String t : TOKEN.split(s.toLowerCase(Locale.ROOT))) if (t.length() > 2) out.add(t);
        return out;
    }

    static int words(String s) { return s == null || s.isBlank() ? 0 : s.trim().split("\\s+").length; }
}
