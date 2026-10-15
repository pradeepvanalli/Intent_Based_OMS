package ai.intentoms.workflow.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deploy-time settings for the workflow module ({@code oms.workflow.*}).
 *
 * @param enabled          start the polling workers and the task sweeper (set false in tests and drive {@link WorkflowWorker#runOnce()})
 * @param workers          number of virtual-thread workers polling for due instances (default 8)
 * @param pollMs           idle poll interval per worker in milliseconds (default 200)
 * @param maxStepAttempts  step re-runs after unexpected exceptions before the instance is FAILED (default 20)
 * @param stepBackoffInitial first backoff after an unexpected step exception (default 1 s; doubles each attempt)
 * @param stepBackoffMax   cap for the step backoff (default 5 min)
 * @param runOnceLimit     safety cap on instances processed by one {@link WorkflowWorker#runOnce()} call
 */
@ConfigurationProperties(prefix = "oms.workflow")
public record WorkflowProperties(@DefaultValue("true") boolean enabled, @DefaultValue("8") int workers, @DefaultValue("200") long pollMs,
                                 @DefaultValue("20") int maxStepAttempts, @DefaultValue("1s") Duration stepBackoffInitial,
                                 @DefaultValue("5m") Duration stepBackoffMax, @DefaultValue("10000") int runOnceLimit) {
    public WorkflowProperties {
        if (workers <= 0) workers = 8;
        if (pollMs <= 0) pollMs = 200;
        if (maxStepAttempts <= 0) maxStepAttempts = 20;
        if (stepBackoffInitial == null || stepBackoffInitial.isNegative() || stepBackoffInitial.isZero()) stepBackoffInitial = Duration.ofSeconds(1);
        if (stepBackoffMax == null || stepBackoffMax.compareTo(stepBackoffInitial) < 0) stepBackoffMax = Duration.ofMinutes(5);
        if (runOnceLimit <= 0) runOnceLimit = 10_000;
    }

    /** Exponential backoff for unexpected step failures: initial × 2^(attempt−1), capped (FR-WF-02). */
    public Duration stepBackoff(int failedAttempt) {
        double ms = stepBackoffInitial.toMillis() * Math.pow(2, Math.max(0, failedAttempt - 1));
        return Duration.ofMillis((long) Math.min(ms, (double) stepBackoffMax.toMillis()));
    }
}
