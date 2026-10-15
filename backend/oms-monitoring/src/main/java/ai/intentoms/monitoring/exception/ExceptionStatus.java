package ai.intentoms.monitoring.exception;

import java.util.Set;

/** Exception lifecycle (FR-MON-03): OPEN -> IN_PROGRESS -> RESOLVED | CANCELLED. */
public final class ExceptionStatus {
    private ExceptionStatus() {}
    public static final String OPEN = "OPEN";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String RESOLVED = "RESOLVED";
    public static final String CANCELLED = "CANCELLED";
    public static final Set<String> ACTIVE = Set.of(OPEN, IN_PROGRESS);
    public static boolean isActive(String s) { return ACTIVE.contains(s); }
}
