package ai.intentoms.decision.definition;

import ai.intentoms.decision.model.Bands;
import ai.intentoms.decision.model.CacheDef;
import ai.intentoms.decision.model.CompositeDef;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.EvaluationSpec;
import ai.intentoms.decision.model.LintReport.LintIssue;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.model.TopKDef;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the PRD Appendix B YAML format (FR-DEC-01) extended with {@code rules}, {@code composite}, {@code topK},
 * {@code cache}, {@code mainQuestion}, {@code status}, {@code canaryPercent}, {@code changeReason}, {@code ticket}.
 * Structural validation lives here (GR-02 bands, GR-03 escape option, Jev limits); content lint lives in the studio.
 */
@Component
public class DecisionDefinitionParser {
    private static final Set<String> VALID_STATUS = Set.of("draft", "pending_approval", "approved", "shadow", "canary", "live", "retired");

    /** Parsed definition plus structural issues; {@code definition} is null only when the YAML is not even a map. */
    public record Parsed(DecisionDefinition definition, List<LintIssue> issues) {
        public boolean ok() { return definition != null && issues.stream().noneMatch(i -> "error".equals(i.severity())); }
    }

    /** Strict parse: throws {@link ValidationException} listing every structural problem. */
    public DecisionDefinition parse(String yaml) {
        Parsed p = parseLenient(yaml);
        if (!p.ok()) {
            List<String> msgs = p.issues().stream().filter(i -> "error".equals(i.severity())).map(i -> i.ref() == null ? i.message() : i.ref() + ": " + i.message()).toList();
            throw new ValidationException("Invalid decision definition: " + String.join("; ", msgs), Map.of("issues", p.issues()));
        }
        return p.definition();
    }

    /** Lenient parse used by lint: collects issues instead of throwing wherever the structure allows. */
    @SuppressWarnings("unchecked")
    public Parsed parseLenient(String yaml) {
        List<LintIssue> issues = new ArrayList<>();
        Map<String, Object> m;
        try {
            m = Json.yamlMap(yaml);
        } catch (IllegalArgumentException e) {
            return new Parsed(null, List.of(LintIssue.error("yaml_syntax", e.getMessage(), null)));
        }
        if (m == null) return new Parsed(null, List.of(LintIssue.error("yaml_empty", "Definition is empty", null)));

        String name = str(m.get("decision"));
        if (name == null || !name.matches("[a-z][a-z0-9_]*")) issues.add(LintIssue.error("name", "'decision' must be a snake_case identifier", "decision"));
        int version = intOf(m.get("version"), 1);
        if (version < 1) issues.add(LintIssue.error("version", "'version' must be >= 1", "version"));
        String owner = str(m.get("owner"));
        Tier tier = null;
        String tierText = str(m.get("tier"));
        if (tierText == null) issues.add(LintIssue.error("tier_missing", "'tier' is required (T0..T3)", "tier"));
        else try { tier = Tier.valueOf(tierText.trim().toUpperCase()); } catch (IllegalArgumentException e) { issues.add(LintIssue.error("tier_invalid", "Unknown tier " + tierText, "tier")); }
        String provider = str(m.getOrDefault("provider", "jev"));
        String fallback = str(m.get("fallbackProvider"));
        int timeoutMs = intOf(m.get("timeoutMs"), DecisionDefinition.DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0) issues.add(LintIssue.error("timeout", "'timeoutMs' must be positive", "timeoutMs"));
        Object dflt = m.get("default");

        List<String> include = new ArrayList<>();
        Object state = m.get("state");
        if (state instanceof Map<?, ?> sm && sm.get("include") instanceof List<?> l) l.forEach(p -> include.add(String.valueOf(p)));
        else if (state instanceof List<?> l) l.forEach(p -> include.add(String.valueOf(p)));

        Map<String, QuestionDef> questions = new LinkedHashMap<>();
        Object qs = m.get("questions");
        if (!(qs instanceof Map<?, ?> qm) || qm.isEmpty()) issues.add(LintIssue.error("questions_missing", "At least one question is required", "questions"));
        else qm.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (!(v instanceof Map<?, ?> qv)) { issues.add(LintIssue.error("question_shape", "Question must be a map", "questions." + key)); return; }
            QuestionDef q = parseQuestion(key, (Map<String, Object>) qv, issues);
            if (q != null) questions.put(key, q);
        });

        Bands bands = null;
        if (m.get("bands") instanceof Map<?, ?> bm) {
            try {
                Double act = nested(bm, "act", "confidence_gte");
                Double confirm = nested(bm, "confirm", "confidence_gte");
                Bands defaults = tier != null ? tier.defaultBands() : Tier.T1.defaultBands();
                double a = act != null ? act : defaults.actGte();
                double c = confirm != null ? confirm : Math.min(defaults.confirmGte(), a);
                bands = new Bands(a, c);
            } catch (IllegalArgumentException e) { issues.add(LintIssue.error("bands", e.getMessage(), "bands")); }
        }

        List<String> pre = strList(m.get("preChecks"));
        List<String> post = strList(m.get("postChecks"));

        List<RuleDef> rules = new ArrayList<>();
        if (m.get("rules") instanceof List<?> rl) {
            int i = 0;
            for (Object r : rl) {
                String ref = "rules[" + i + "]";
                if (r instanceof Map<?, ?> rm) {
                    String id = str(rm.get("id")) != null ? str(rm.get("id")) : "rule_" + i;
                    String when = str(rm.get("when"));
                    Object then = rm.get("then");
                    if (when == null || when.isBlank()) issues.add(LintIssue.error("rule_when", "Rule needs a 'when' CEL expression", ref));
                    if (then == null) issues.add(LintIssue.error("rule_then", "Rule needs a 'then' (value | escalate | block)", ref));
                    rules.add(new RuleDef(id, when, then == null ? null : String.valueOf(then), str(rm.get("reason")), intOf(rm.get("priority"), 100)));
                } else issues.add(LintIssue.error("rule_shape", "Rule must be a map", ref));
                i++;
            }
            rules.sort(java.util.Comparator.comparingInt(RuleDef::priority));
        }

        CompositeDef composite = null;
        if (m.get("composite") instanceof Map<?, ?> cm) {
            Map<String, Double> w = new LinkedHashMap<>();
            if (cm.get("weights") instanceof Map<?, ?> wm) wm.forEach((k, v) -> w.put(String.valueOf(k), v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v))));
            if (w.isEmpty()) issues.add(LintIssue.error("composite_weights", "composite.weights must list at least one score question", "composite"));
            for (String k : w.keySet()) {
                QuestionDef q = questions.get(k);
                if (q == null) issues.add(LintIssue.error("composite_unknown", "composite weight references unknown question " + k, "composite.weights." + k));
                else if (!q.isScore()) issues.add(LintIssue.error("composite_not_score", "composite weight " + k + " must reference a score question", "composite.weights." + k));
            }
            composite = new CompositeDef(w, str(cm.get("outputKey")));
        }

        TopKDef topK = null;
        if (m.get("topK") instanceof Map<?, ?> tm) {
            try {
                String sq = str(tm.get("scoreQuestion"));
                if (sq == null || !questions.containsKey(sq) || !questions.get(sq).isScore()) issues.add(LintIssue.error("topk_question", "topK.scoreQuestion must name a score question", "topK"));
                String cf = str(tm.get("candidatesField"));
                if (cf == null) issues.add(LintIssue.error("topk_candidates", "topK.candidatesField is required", "topK"));
                topK = new TopKDef(sq, intOf(tm.get("k"), 20), cf);
            } catch (IllegalArgumentException e) { issues.add(LintIssue.error("topk", e.getMessage(), "topK")); }
        }

        EvaluationSpec evaluation = null;
        if (m.get("evaluation") instanceof Map<?, ?> em) {
            String gs = str(em.get("goldenSet"));
            if (gs == null) issues.add(LintIssue.error("evaluation_golden_set", "evaluation.goldenSet is required", "evaluation"));
            evaluation = new EvaluationSpec(gs, dbl(em.get("minAccuracyInActBand"), 0.9), dbl(em.get("maxCalibrationError"), 0.05));
        }

        CacheDef cache = null;
        if (m.get("cache") instanceof Map<?, ?> cm2 && cm2.get("ttl") != null) {
            try { cache = new CacheDef(Duration.parse(String.valueOf(cm2.get("ttl")))); }
            catch (Exception e) { issues.add(LintIssue.error("cache_ttl", "cache.ttl must be an ISO-8601 duration such as PT10M", "cache")); }
        }

        String mainQuestion = str(m.get("mainQuestion"));
        if (mainQuestion != null && !questions.containsKey(mainQuestion)) issues.add(LintIssue.error("main_question", "mainQuestion references unknown question " + mainQuestion, "mainQuestion"));

        String status = str(m.getOrDefault("status", "draft"));
        if (!VALID_STATUS.contains(status)) issues.add(LintIssue.error("status", "Unknown status " + status, "status"));
        int canary = intOf(m.get("canaryPercent"), 0);
        if (canary < 0 || canary > 100) issues.add(LintIssue.error("canary", "canaryPercent must be 0..100", "canaryPercent"));

        // GR-02: a decision whose main question is probabilistic needs bands; defaults by tier always exist, so only check T3 misuse.
        if (tier != null && tier.neverActs() && bands != null && !bands.neverActs()) {
            issues.add(LintIssue.warning("t3_bands", "Tier T3 never acts; the act band is ignored and every answer is a recommendation", "bands.act"));
        }
        if (!questions.isEmpty() && questions.values().stream().noneMatch(QuestionDef::isChoice) && composite == null && mainQuestion == null && questions.size() > 1) {
            issues.add(LintIssue.warning("main_question_implicit", "No choice question, composite or mainQuestion: the first question's answer becomes the value", "questions"));
        }

        DecisionDefinition def = null;
        if (tier != null && name != null) {
            try {
                def = new DecisionDefinition(name, version, owner, tier, provider, fallback, timeoutMs, dflt, include, questions, bands, pre, post, rules, composite, topK,
                    evaluation, cache, mainQuestion, status, canary, str(m.get("changeReason")), str(m.get("ticket")), yaml, null);
            } catch (IllegalArgumentException e) { issues.add(LintIssue.error("definition", e.getMessage(), null)); }
        }
        return new Parsed(def, issues);
    }

    @SuppressWarnings("unchecked")
    private QuestionDef parseQuestion(String key, Map<String, Object> q, List<LintIssue> issues) {
        String ref = "questions." + key;
        String type = str(q.get("type"));
        if (type == null) { issues.add(LintIssue.error("question_type", "Question needs a type (choice|score|noul)", ref)); return null; }
        type = type.toLowerCase();
        String instructions = str(q.get("instructions"));
        String statement = str(q.get("statement"));
        boolean reasonCode = Boolean.TRUE.equals(q.get("reasonCode"));
        String feeds = str(q.get("feeds"));
        String escape = str(q.get("escapeOption"));
        Map<String, String> criteriaMap = new LinkedHashMap<>();
        List<String> levels = new ArrayList<>();
        Object criteria = q.get("criteria");
        switch (type) {
            case QuestionDef.CHOICE -> {
                if (!(criteria instanceof Map<?, ?> cm) || cm.isEmpty()) { issues.add(LintIssue.error("choice_criteria", "Choice needs a criteria map option -> description", ref + ".criteria")); break; }
                for (var e : cm.entrySet()) {
                    String opt = String.valueOf(e.getKey());
                    Object v = e.getValue();
                    if (v instanceof Map<?, ?> om) {           // {description: ..., escape: true}
                        Object desc = om.get("description");
                        criteriaMap.put(opt, desc == null ? "" : String.valueOf(desc));
                        if (Boolean.TRUE.equals(om.get("escape"))) escape = opt;
                    } else criteriaMap.put(opt, v == null ? "" : String.valueOf(v));
                }
                if (criteriaMap.size() > QuestionDef.MAX_CHOICE_OPTIONS) issues.add(LintIssue.error("choice_too_many", "Choice has more than 255 options; use topK", ref + ".criteria"));
                if (criteriaMap.size() < 2) issues.add(LintIssue.error("choice_too_few", "Choice needs at least two options (one being the escape option)", ref + ".criteria"));
                if (instructions == null) issues.add(LintIssue.error("choice_instructions", "Choice needs instructions", ref + ".instructions"));
                QuestionDef probe = new QuestionDef(key, type, instructions, criteriaMap, List.of(), statement, reasonCode, feeds, escape);
                if (probe.escape() == null) issues.add(LintIssue.error("missing_escape_option", "Every Choice needs an escape option (unclear | none_of_these | not_enough_information | other | escalate, or an option marked escape: true) (GR-03)", ref + ".criteria"));
            }
            case QuestionDef.SCORE -> {
                if (criteria instanceof List<?> cl) cl.forEach(l -> levels.add(String.valueOf(l)));
                else if (criteria instanceof Map<?, ?> cm) cm.values().forEach(l -> levels.add(String.valueOf(l)));
                if (levels.size() < 2 || levels.size() > 10) issues.add(LintIssue.error("score_levels", "Score needs 2..10 rubric levels, low to high", ref + ".criteria"));
                if (instructions == null) issues.add(LintIssue.error("score_instructions", "Score needs instructions", ref + ".instructions"));
            }
            case QuestionDef.NOUL -> {
                if (statement == null && instructions == null) issues.add(LintIssue.error("noul_statement", "Noul needs a statement", ref + ".statement"));
                if (criteria instanceof Map<?, ?> cm) cm.forEach((k, v) -> criteriaMap.put(String.valueOf(k), String.valueOf(v)));
                if (!criteriaMap.isEmpty() && !(criteriaMap.keySet().equals(Set.of("true", "false")))) issues.add(LintIssue.error("noul_criteria", "Noul criteria, when given, must have exactly the keys true and false", ref + ".criteria"));
            }
            default -> { issues.add(LintIssue.error("question_type", "Unknown question type " + type, ref + ".type")); return null; }
        }
        return new QuestionDef(key, type, instructions, criteriaMap, levels, statement, reasonCode, feeds, escape);
    }

    private static Double nested(Map<?, ?> m, String k1, String k2) {
        Object v = m.get(k1);
        if (v instanceof Map<?, ?> im && im.get(k2) != null) return dbl(im.get(k2), 0);
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static int intOf(Object o, int d) { return o instanceof Number n ? n.intValue() : o == null ? d : Integer.parseInt(String.valueOf(o).trim()); }
    static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : o == null ? d : Double.parseDouble(String.valueOf(o).trim()); }
    static List<String> strList(Object o) { List<String> out = new ArrayList<>(); if (o instanceof List<?> l) l.forEach(e -> out.add(String.valueOf(e))); return out; }
}
