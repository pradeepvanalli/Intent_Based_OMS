package ai.intentoms.workflow.model;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** What a {@link Step} asks the engine to do after it returns (FR-WF-01). */
public sealed interface Next {
    /** Continue with another step immediately. */
    record GoTo(String step) implements Next {
        public GoTo { if (step == null || step.isBlank()) throw new IllegalArgumentException("step is required"); }
    }

    /**
     * Park until one of {@code names} is signalled (FR-WF-05). The step named {@code onSignalStep} (or the current
     * step when null) runs with {@link WorkflowContext#signal()} set. When {@code timeout} elapses first, the engine
     * runs {@code onTimeoutStep}; a null timeout waits forever and a null onTimeoutStep fails the instance on timeout.
     */
    record WaitSignal(Set<String> names, Duration timeout, String onTimeoutStep, String onSignalStep) implements Next {
        public WaitSignal {
            if (names == null || names.isEmpty()) throw new IllegalArgumentException("at least one signal name is required");
            names = Set.copyOf(names);
        }
    }

    /** Durable timer: resume in {@code thenStep} after {@code d}. */
    record Sleep(Duration d, String thenStep) implements Next {
        public Sleep {
            if (d == null || d.isNegative()) throw new IllegalArgumentException("duration must be >= 0");
            if (thenStep == null || thenStep.isBlank()) throw new IllegalArgumentException("thenStep is required");
        }
    }

    /** Terminal success; {@code result} is stored on the instance and published in {@code workflow.completed}. */
    record Complete(Map<String, Object> result) implements Next {
        public Complete { result = result == null ? Map.of() : Map.copyOf(result); }
    }

    /** Terminal failure. With {@code compensate} the recorded compensations run in reverse first (FR-WF-02). */
    record Fail(String reason, boolean compensate) implements Next {}

    /**
     * Park until human task {@code taskId} completes (FR-WF-04). The completion arrives as signal {@code task:<taskId>}
     * whose payload carries {@code outcome}, {@code reason} and {@code details}.
     */
    record WaitTask(String taskId, String onCompleteStep, Duration timeout, String onTimeoutStep) implements Next {
        public WaitTask {
            if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
            if (onCompleteStep == null || onCompleteStep.isBlank()) throw new IllegalArgumentException("onCompleteStep is required");
        }
    }

    static Next goTo(String step) { return new GoTo(step); }
    static Next waitSignal(Set<String> names, Duration timeout, String onTimeoutStep, String onSignalStep) { return new WaitSignal(names, timeout, onTimeoutStep, onSignalStep); }
    static Next waitSignal(String name, String onSignalStep) { return new WaitSignal(Set.of(name), null, null, onSignalStep); }
    static Next sleep(Duration d, String thenStep) { return new Sleep(d, thenStep); }
    static Next complete(Map<String, Object> result) { return new Complete(result); }
    static Next fail(String reason, boolean compensate) { return new Fail(reason, compensate); }
    static Next waitTask(String taskId, String onCompleteStep, Duration timeout, String onTimeoutStep) { return new WaitTask(taskId, onCompleteStep, timeout, onTimeoutStep); }
}
