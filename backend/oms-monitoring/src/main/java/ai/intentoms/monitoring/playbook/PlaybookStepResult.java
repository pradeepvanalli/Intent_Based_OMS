package ai.intentoms.monitoring.playbook;

import java.util.Map;

/** Outcome returned by a {@link PlaybookAction}: {@code done}, {@code skipped} (nothing to do) or {@code failed}. */
public record PlaybookStepResult(String status, String detail, Map<String, Object> data) {
    public PlaybookStepResult { data = data == null ? Map.of() : Map.copyOf(data); }
    public static PlaybookStepResult done(String detail) { return new PlaybookStepResult(PlaybookStep.DONE, detail, Map.of()); }
    public static PlaybookStepResult done(String detail, Map<String, Object> data) { return new PlaybookStepResult(PlaybookStep.DONE, detail, data); }
    public static PlaybookStepResult skipped(String detail) { return new PlaybookStepResult(PlaybookStep.SKIPPED, detail, Map.of()); }
    public static PlaybookStepResult failed(String detail) { return new PlaybookStepResult(PlaybookStep.FAILED, detail, Map.of()); }
    public boolean failed() { return PlaybookStep.FAILED.equals(status); }
}
