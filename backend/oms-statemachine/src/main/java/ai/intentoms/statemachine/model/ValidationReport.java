package ai.intentoms.statemachine.model;

import java.util.List;

/** Result of the static checks (FR-SM-10): {@code ok} is true when no issue has severity {@code error}; warnings never block publishing. */
public record ValidationReport(boolean ok, List<ValidationIssue> issues) {
    public ValidationReport { issues = issues == null ? List.of() : List.copyOf(issues); }

    public static ValidationReport of(List<ValidationIssue> issues) {
        return new ValidationReport(issues.stream().noneMatch(ValidationIssue::isError), issues);
    }

    public List<ValidationIssue> errors() { return issues.stream().filter(ValidationIssue::isError).toList(); }
    public List<ValidationIssue> warnings() { return issues.stream().filter(i -> !i.isError()).toList(); }
}
