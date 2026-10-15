package ai.intentoms.copilot.change;

import java.util.ArrayList;
import java.util.List;

/** Deterministic validator outcome for one proposed artefact: errors block staging, warnings do not. */
public record ValidationResult(boolean ok, List<String> issues, List<String> warnings) {
    public ValidationResult { issues = issues == null ? List.of() : List.copyOf(issues); warnings = warnings == null ? List.of() : List.copyOf(warnings); }

    public static ValidationResult of(List<String> issues, List<String> warnings) { return new ValidationResult(issues.isEmpty(), issues, warnings); }
    public static ValidationResult passed() { return new ValidationResult(true, List.of(), List.of()); }
    public static ValidationResult error(String message) { return new ValidationResult(false, List.of(message), List.of()); }

    /** Builder used by the validators. */
    public static final class Collector {
        private final List<String> issues = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        public Collector error(String m) { issues.add(m); return this; }
        public Collector warn(String m) { warnings.add(m); return this; }
        public boolean hasErrors() { return !issues.isEmpty(); }
        public ValidationResult result() { return ValidationResult.of(issues, warnings); }
    }
}
