package ai.intentoms.monitoring.playbook;

import java.time.Instant;

/** One recorded playbook step on an exception (UI contract item 10): {@code status} is {@code done|skipped|failed}; {@code gatedBy} names the gate that skipped it. */
public record PlaybookStep(String action, String status, String detail, String gatedBy, Instant at) {
    public static final String DONE = "done";
    public static final String SKIPPED = "skipped";
    public static final String FAILED = "failed";
}
