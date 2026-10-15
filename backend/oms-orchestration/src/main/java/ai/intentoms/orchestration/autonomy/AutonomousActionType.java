package ai.intentoms.orchestration.autonomy;

import java.util.Set;

/**
 * Remediation action types the Agentic Control Tower can take (or recommend). {@code REFUND} and {@code CANCEL} are
 * modelled only so the "never autonomous" hard rule (GR-05 spirit: money-moving / irreversible actions always need a
 * person) can be checked and tested; the {@code stuck_order_diagnosis} decision never maps to them.
 */
public enum AutonomousActionType {
    RE_SOURCE("re_source", true),
    RE_PROMISE("re_promise", true),
    RETRY_PAYMENT("retry_payment", false),
    RELEASE_HOLD("release_hold", true),
    NOTIFY_CUSTOMER("notify_customer", false),
    REFUND("refund", false),
    CANCEL("cancel", false);

    /** Action types the guardrail allow-list may ever contain, regardless of tenant configuration (GR hard rule). */
    public static final Set<AutonomousActionType> NEVER_AUTONOMOUS = Set.of(REFUND, CANCEL);

    private final String code;
    private final boolean undoable;

    AutonomousActionType(String code, boolean undoable) {
        this.code = code;
        this.undoable = undoable;
    }

    public String code() { return code; }

    /** Whether a reverse operation is implemented for this type (re_source, re_promise, release_hold). */
    public boolean undoable() { return undoable; }

    public static AutonomousActionType fromCode(String code) {
        for (AutonomousActionType t : values()) if (t.code.equals(code)) return t;
        throw new IllegalArgumentException("Unknown autonomous action type: " + code);
    }

    public static boolean isKnownCode(String code) {
        for (AutonomousActionType t : values()) if (t.code.equals(code)) return true;
        return false;
    }
}
