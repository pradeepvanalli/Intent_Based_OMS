package ai.intentoms.decision.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One atomic Jev question (PRD section 9 rule 1). Type is choice | score | noul.
 *
 * @param key            question key; also the answer key
 * @param type           choice | score | noul
 * @param instructions   the question text (for noul the statement is used when instructions are absent)
 * @param choiceCriteria option key to description for choice (must include an escape option, GR-03); for noul the optional
 *                       {"true": ..., "false": ...} descriptions
 * @param scoreLevels    rubric level descriptions low to high (2..10) for score
 * @param statement      the yes/no statement for noul
 * @param reasonCode     true when this question is a reason-code Noul stored alongside the main answer (section 9 rule 6)
 * @param feeds          what downstream consumer the answer feeds (documentation only)
 * @param escapeOption   explicit escape option key (null = detected from {@link #ESCAPE_KEYS})
 */
public record QuestionDef(String key, String type, String instructions, Map<String, String> choiceCriteria, List<String> scoreLevels,
                          String statement, boolean reasonCode, String feeds, String escapeOption) {
    public static final String CHOICE = "choice";
    public static final String SCORE = "score";
    public static final String NOUL = "noul";
    /** Option keys recognised as escape options without explicit marking (GR-03). */
    public static final Set<String> ESCAPE_KEYS = Set.of("unclear", "none_of_these", "not_enough_information", "other", "escalate", "unclassified");
    public static final int MAX_CHOICE_OPTIONS = 255;

    public QuestionDef {
        choiceCriteria = choiceCriteria == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(choiceCriteria));
        scoreLevels = scoreLevels == null ? List.of() : List.copyOf(scoreLevels);
    }

    public boolean isChoice() { return CHOICE.equals(type); }
    public boolean isScore() { return SCORE.equals(type); }
    public boolean isNoul() { return NOUL.equals(type); }

    /** Text sent as the question's instructions: explicit instructions, else the noul statement. */
    public String effectiveInstructions() { return instructions != null && !instructions.isBlank() ? instructions : statement; }

    /** The escape option of a choice: explicitly marked, else the first key found in {@link #ESCAPE_KEYS}; null when none. */
    public String escape() {
        if (!isChoice()) return null;
        if (escapeOption != null && choiceCriteria.containsKey(escapeOption)) return escapeOption;
        for (String k : choiceCriteria.keySet()) if (ESCAPE_KEYS.contains(k)) return k;
        return null;
    }

    public boolean isEscape(Object value) { return value != null && isChoice() && value.toString().equals(escape()); }

    /** Number of score levels (0 for non-score questions). */
    public int levels() { return scoreLevels.size(); }
}
