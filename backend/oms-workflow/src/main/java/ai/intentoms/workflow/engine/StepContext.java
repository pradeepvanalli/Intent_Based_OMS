package ai.intentoms.workflow.engine;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.model.RetryPolicy;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.WorkflowContext;
import ai.intentoms.workflow.model.WorkflowDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link WorkflowContext} bound to one step execution. Lives inside the instance row-lock transaction opened by
 * {@link StepRunner}; activity bodies run in a nested savepoint so a failing body cannot poison the outer transaction
 * and its partial writes are undone before the attempt is recorded (FR-WF-02).
 */
final class StepContext implements WorkflowContext {
    private final WorkflowInstance instance;
    private final WorkflowDefinition definition;
    private final String step;
    private final Signal signal;
    private final Map<String, Object> vars;
    private final List<Map<String, Object>> compensations;
    private final Map<String, Runnable> inMemoryUndos = new LinkedHashMap<>();
    private final WorkflowRepository repo;
    private final TransactionTemplate nested;
    private final TimeSource time;
    private final Metrics metrics;
    private final ScheduledExecutorService watchdog;

    StepContext(WorkflowInstance instance, WorkflowDefinition definition, String step, Signal signal, Map<String, Object> vars,
                WorkflowRepository repo, TransactionTemplate nested, TimeSource time, Metrics metrics, ScheduledExecutorService watchdog) {
        this.instance = instance;
        this.definition = definition;
        this.step = step;
        this.signal = signal;
        this.vars = new LinkedHashMap<>(vars);
        this.compensations = new ArrayList<>(instance.compensations());
        this.repo = repo;
        this.nested = nested;
        this.time = time;
        this.metrics = metrics;
        this.watchdog = watchdog;
    }

    @Override public String workflowId() { return instance.id(); }
    @Override public String businessKey() { return instance.businessKey(); }
    @Override public String tenantId() { return instance.tenantId(); }
    @Override public String type() { return instance.type(); }
    @Override public int version() { return instance.version(); }
    @Override public String step() { return step; }
    @Override public int attempt() { return instance.attempt(); }
    @Override public Map<String, Object> vars() { return vars; }
    @Override public Optional<Signal> signal() { return Optional.ofNullable(signal); }
    @Override public Instant now() { return time.now(); }

    /** Compensation entries after this execution (durable list, newest last). */
    List<Map<String, Object>> compensations() { return compensations; }

    /** In-process undo closures registered during this execution, by compensation name. */
    Map<String, Runnable> inMemoryUndos() { return inMemoryUndos; }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T activity(String name, RetryPolicy policy, Callable<T> body) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("activity name is required");
        RetryPolicy p = policy == null ? RetryPolicy.standard() : policy;
        Optional<WorkflowRepository.ActivityRow> existing = repo.findActivity(instance.id(), step, name);
        if (existing.isPresent() && "OK".equals(existing.get().status())) {
            repo.timeline(instance.id(), time.now(), "activity", name, "replayed", Map.of("step", step, "attempt", existing.get().attempt()));
            return (T) existing.get().result();
        }
        int attempt = existing.map(WorkflowRepository.ActivityRow::attempt).orElse(0) + 1;
        String rowId = existing.map(WorkflowRepository.ActivityRow::id).orElseGet(Ids::id);
        Instant started = time.now();
        long t0 = System.nanoTime();
        T result;
        try {
            result = runGuarded(name, body);
        } catch (Exception e) {
            Instant finished = time.now();
            String error = abbreviate(e.toString());
            repo.upsertActivity(rowId, instance.id(), step, name, attempt, "FAILED", null, error, started, finished);
            metrics.record("oms.workflow.activity", Duration.ofNanos(System.nanoTime() - t0), "type", instance.type(), "activity", name, "outcome", "failed");
            if (attempt < p.maxAttempts()) {
                Duration backoff = p.backoffAfter(attempt);
                repo.timeline(instance.id(), finished, "activity", name, "retry",
                    Map.of("step", step, "attempt", attempt, "maxAttempts", p.maxAttempts(), "error", error, "retryInMs", backoff.toMillis()));
                throw new RetryLater(backoff, name, e);
            }
            repo.timeline(instance.id(), finished, "activity", name, "failed", Map.of("step", step, "attempt", attempt, "maxAttempts", p.maxAttempts(), "error", error));
            throw new ActivityFailedException(name, attempt, e);
        }
        Object jsonFriendly = result == null ? null : Json.read(Json.write(result), Object.class);
        Instant finished = time.now();
        repo.upsertActivity(rowId, instance.id(), step, name, attempt, "OK", jsonFriendly, null, started, finished);
        repo.timeline(instance.id(), finished, "activity", name, "ok", Map.of("step", step, "attempt", attempt, "durationMs", elapsedMs(t0)));
        metrics.record("oms.workflow.activity", Duration.ofNanos(System.nanoTime() - t0), "type", instance.type(), "activity", name, "outcome", "ok");
        return (T) jsonFriendly;
    }

    /**
     * Run the body inside a savepoint with an interrupt watchdog armed at the definition's activity timeout.
     * Checked exceptions from the body are unwrapped and rethrown as-is; an interrupt fired by the watchdog becomes an
     * {@link ActivityTimeoutException}. The savepoint is rolled back on any failure so the outer transaction stays usable.
     */
    private <T> T runGuarded(String name, Callable<T> body) throws Exception {
        Duration timeout = definition.defaultActivityTimeout();
        boolean guard = timeout != null && !timeout.isZero() && !timeout.isNegative();
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> interrupter = guard ? watchdog.schedule(worker::interrupt, timeout.toMillis(), TimeUnit.MILLISECONDS) : null;
        try {
            return nested.execute(status -> {
                try {
                    return body.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CheckedWrapper(e);
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e instanceof CheckedWrapper ? e.getCause() : e;
            boolean fired = interrupter != null && !interrupter.cancel(false);
            Thread.interrupted();
            if (fired) throw new ActivityTimeoutException(name, timeout, cause);
            if (cause instanceof Exception ex) throw ex;
            throw e;
        } finally {
            if (interrupter != null) interrupter.cancel(false);
            Thread.interrupted();
        }
    }

    @Override
    public void compensation(String name, Runnable undo) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("compensation name is required");
        compensations.removeIf(c -> step.equals(c.get("step")) && name.equals(c.get("name")));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("step", step);
        entry.put("vars", new LinkedHashMap<>(vars));
        entry.put("registeredAt", time.now().toString());
        entry.put("durable", definition.compensations().containsKey(name));
        compensations.add(entry);
        if (undo != null) inMemoryUndos.put(name, undo);
        repo.timeline(instance.id(), time.now(), "compensation", name, "registered", Map.of("step", step, "durable", entry.get("durable")));
    }

    @Override
    public void log(String message, Map<String, Object> details) {
        Map<String, Object> d = new LinkedHashMap<>(details == null ? Map.of() : details);
        d.put("step", step);
        repo.timeline(instance.id(), time.now(), "log", message, null, d);
    }

    private static long elapsedMs(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    static String abbreviate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
    }

    /** Carries a checked exception through {@link TransactionTemplate#execute}. */
    private static final class CheckedWrapper extends RuntimeException {
        CheckedWrapper(Exception cause) { super(cause); }
    }
}
