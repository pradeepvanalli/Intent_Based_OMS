package ai.intentoms.decision.model;

import java.util.List;

/** Studio lint result (FR-PRM-05, FR-PRM-08, GR-16). Errors block submission; warnings do not. */
public record LintReport(boolean ok, List<LintIssue> issues) {
    public record LintIssue(String severity, String code, String message, String ref) {
        public static LintIssue error(String code, String message, String ref) { return new LintIssue("error", code, message, ref); }
        public static LintIssue warning(String code, String message, String ref) { return new LintIssue("warning", code, message, ref); }
    }
    public static LintReport of(List<LintIssue> issues) { return new LintReport(issues.stream().noneMatch(i -> "error".equals(i.severity())), List.copyOf(issues)); }
    public long errors() { return issues.stream().filter(i -> "error".equals(i.severity())).count(); }
}
