package ai.intentoms.statemachine.model;

/** One finding of the static checks (FR-SM-10). {@code severity} is {@code error} or {@code warning}; {@code ref} points at the state/transition/rule. */
public record ValidationIssue(String severity, String code, String message, String ref) {
    public static final String ERROR = "error";
    public static final String WARNING = "warning";

    public static ValidationIssue error(String code, String message, String ref) { return new ValidationIssue(ERROR, code, message, ref); }
    public static ValidationIssue warning(String code, String message, String ref) { return new ValidationIssue(WARNING, code, message, ref); }
    public boolean isError() { return ERROR.equals(severity); }
}
