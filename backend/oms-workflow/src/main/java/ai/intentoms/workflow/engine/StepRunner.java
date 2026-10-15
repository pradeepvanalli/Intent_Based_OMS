package ai.intentoms.workflow.engine;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowDefinition;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes one due workflow instance per call (FR-WF-01, FR-WF-02, FR-WF-05): lock the row, work out what woke it
 * (queued signal, timer, task completion or a plain RUNNING step), run the step handler, apply the returned
 * {@link Next} and persist, all in one transaction.
 *
 * <p><b>Transaction trade-off.</b> Lock + step + persist share a single transaction, so a crash anywhere leaves the
 * instance exactly as it was and the row lock serialises steps and signals per instance with no coordinator. The cost
 * is that activity bodies run while the row (and a connection) is held: keep them short and push slow external calls
 * into separate activities with retry policies. Activity bodies run in a savepoint so their failure cannot abort the
 * surrounding transaction; the persisted attempt log is what makes replays idempotent. If the surrounding transaction
 * itself fails (e.g. a step issued SQL outside an activity that aborted the connection), the failure is recorded in a
 * fresh transaction with the usual backoff so the instance is never retried in a hot loop.
 */
@Component
public class StepRunner {
    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);
    static final String AGGREGATE = "workflow";
    static final String COMPENSATE_STEP = "compensate";

    private final WorkflowRepository repo;
    private final WorkflowRegistry registry;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;
    private final WorkflowProperties props;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private final TransactionTemplate nested;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "workflow-activity-watchdog");
        t.setDaemon(true);
        return t;
    });

    public StepRunner(WorkflowRepository repo, WorkflowRegistry registry, EventStore events, TimeSource time, Metrics metrics,
                      WorkflowProperties props, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.registry = registry;
        this.events = events;
        this.time = time;
        this.metrics = metrics;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nested = new TransactionTemplate(txManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** Pick up and run the earliest due instance. Returns false when nothing was due. */
    public boolean runNext() {
        AtomicReference<WorkflowInstance> picked = new AtomicReference<>();
        try {
            Boolean ran = tx.execute(status -> {
                Optional<WorkflowInstance> due = repo.lockNextDue(time.now());
                if (due.isEmpty()) return false;
                WorkflowInstance w = due.get();
                picked.set(w);
                Optional<String> prevTenant = TenantContext.current();
                Authentication prevAuth = SecurityContextHolder.getContext().getAuthentication();
                bind(w.tenantId());
                try {
                    execute(w);
                } finally {
                    unbind(prevTenant, prevAuth);
                }
                return true;
            });
            return Boolean.TRUE.equals(ran);
        } catch (RuntimeException e) {
            WorkflowInstance w = picked.get();
            if (w == null) throw e;
            recordOutOfBandFailure(w, e);
            return true;
        }
    }

    private void bind(String tenant) {
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(tenant)));
    }

    /** Restore whatever the calling thread had bound (tests drive the runner from a thread that already has a tenant and principal). */
    private void unbind(Optional<String> prevTenant, Authentication prevAuth) {
        if (prevAuth == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prevAuth);
        if (prevTenant.isPresent()) TenantContext.set(prevTenant.get()); else TenantContext.clear();
    }

    /** The step's transaction rolled back; record the attempt in a fresh one so the instance backs off instead of spinning. */
    private void recordOutOfBandFailure(WorkflowInstance w, RuntimeException e) {
        log.error("Workflow {} step '{}' transaction failed: {}", w.id(), w.currentStep(), e.toString());
        try {
            txNew.executeWithoutResult(s -> TenantContext.runAs(w.tenantId(), () -> {
                Instant now = time.now();
                int attempt = w.attempt() + 1;
                String error = StepContext.abbreviate(e.toString());
                boolean exhausted = attempt >= props.maxStepAttempts();
                repo.timeline(w.id(), now, "error", w.currentStep(), exhausted ? "failed" : "retry",
                    Map.of("attempt", attempt, "error", error, "transactionRolledBack", true));
                if (exhausted) {
                    repo.recordRetry(w.id(), attempt, error, null, WorkflowStatus.FAILED, now);
                    events.append(AGGREGATE, w.id(), new NewEvent("workflow.failed", Map.of("type", w.type(), "businessKey", w.businessKey(), "reason", error, "step", w.currentStep()), Actor.system("workflow")));
                } else {
                    repo.recordRetry(w.id(), attempt, error, now.plus(props.stepBackoff(attempt)), w.status(), now);
                }
            }));
        } catch (RuntimeException second) {
            log.error("Could not record failure for workflow {}: {}", w.id(), second.toString());
        }
    }

    // ------------------------------------------------------------------ dispatch

    private void execute(WorkflowInstance w) {
        Instant now = time.now();
        Optional<WorkflowDefinition> def = registry.find(w.type(), w.version());
        if (def.isEmpty()) {
            String error = "No definition registered for " + w.type() + " v" + w.version() + "; parked until retryNow()";
            repo.timeline(w.id(), now, "error", w.currentStep(), "parked", Map.of("error", error));
            repo.update(with(w).lastError(error).nextWakeAt(null).updatedAt(now).build());
            log.warn("Workflow {}: {}", w.id(), error);
            return;
        }
        WorkflowDefinition d = def.get();
        switch (w.status()) {
            case WorkflowStatus.RUNNING -> runStep(w, d, w.currentStep(), null, null, now);
            case WorkflowStatus.WAITING_SIGNAL -> wakeFromWait(w, d, w.waitSignals(), w.onSignalStep() != null ? w.onSignalStep() : w.currentStep(), "signal", now);
            case WorkflowStatus.WAITING_TASK -> wakeFromWait(w, d, Set.of("task:" + w.waitTaskId()), w.onTaskStep(), "task", now);
            case WorkflowStatus.WAITING_TIMER -> {
                if (w.timeoutAt() != null && now.isBefore(w.timeoutAt())) {
                    repo.update(with(w).nextWakeAt(w.timeoutAt()).updatedAt(now).build());
                    return;
                }
                repo.timeline(w.id(), now, "timer", w.onTimeoutStep(), "fired", Map.of("scheduledFor", String.valueOf(w.timeoutAt())));
                runStep(w, d, w.onTimeoutStep(), null, null, now);
            }
            case WorkflowStatus.COMPENSATING -> runCompensation(w, d, now);
            default -> {
                log.warn("Workflow {} in status {} was due; parking", w.id(), w.status());
                repo.update(with(w).nextWakeAt(null).updatedAt(now).build());
            }
        }
    }

    /** WAITING_SIGNAL / WAITING_TASK: a matching queued signal wins; otherwise the timeout path; otherwise re-arm. */
    private void wakeFromWait(WorkflowInstance w, WorkflowDefinition d, Set<String> names, String onSignalStep, String kind, Instant now) {
        Optional<WorkflowRepository.SignalRow> sig = repo.nextPendingSignal(w.id(), names);
        if (sig.isPresent()) {
            runStep(w, d, onSignalStep, sig.get().signal(), sig.get(), now);
            return;
        }
        if (w.timeoutAt() != null && !now.isBefore(w.timeoutAt())) {
            repo.timeline(w.id(), now, "timer", w.onTimeoutStep(), "fired", Map.of("waitingFor", new ArrayList<>(names), "kind", kind));
            if ("task".equals(kind) && w.waitTaskId() != null && repo.expireTask(w.tenantId(), w.waitTaskId(), now)) {
                repo.timeline(w.id(), now, "task", w.waitTaskId(), "expired", Map.of());
                events.append("task", w.waitTaskId(), new NewEvent("task.expired", Map.of("taskId", w.waitTaskId(), "workflowId", w.id()), Actor.system("workflow")));
            }
            if (w.onTimeoutStep() == null) {
                fail(w, d, null, "Timed out waiting for " + names, d.compensateOnFailure(), now);
            } else {
                runStep(w, d, w.onTimeoutStep(), null, null, now);
            }
            return;
        }
        repo.update(with(w).nextWakeAt(w.timeoutAt()).updatedAt(now).build());
    }

    // ------------------------------------------------------------------ steps

    private void runStep(WorkflowInstance w, WorkflowDefinition d, String stepName, Signal signal, WorkflowRepository.SignalRow row, Instant now) {
        Step step = stepName == null ? null : d.steps().get(stepName);
        if (step == null) {
            fail(w, d, null, "Step '" + stepName + "' is not defined in " + d.type() + " v" + d.version(), d.compensateOnFailure(), now);
            if (row != null) repo.consumeSignal(row.id(), now);
            return;
        }
        StepContext ctx = new StepContext(w, d, stepName, signal, w.vars(), repo, nested, time, metrics, watchdog);
        long t0 = System.nanoTime();
        String outcome;
        try {
            Next next = step.run(ctx);
            if (next == null) throw new IllegalStateException("Step '" + stepName + "' returned null");
            if (row != null) {
                repo.consumeSignal(row.id(), now);
                repo.timeline(w.id(), now, "signal", signal.name(), "consumed", Map.of("step", stepName, "payload", signal.payload()));
            }
            outcome = apply(w, d, ctx, stepName, next, now);
        } catch (RetryLater r) {
            outcome = "retry";
            scheduleRetry(w, ctx, stepName, r.getMessage(), r.backoff(), now);
        } catch (Throwable t) {
            int attempt = w.attempt() + 1;
            String error = StepContext.abbreviate(t.toString());
            if (attempt >= props.maxStepAttempts() || t instanceof ActivityFailedException) {
                outcome = "failed";
                log.warn("Workflow {} step '{}' failed permanently: {}", w.id(), stepName, error);
                repo.timeline(w.id(), now, "error", stepName, "failed", Map.of("attempt", attempt, "error", error));
                if (row != null) repo.consumeSignal(row.id(), now);
                fail(w, d, ctx, error, d.compensateOnFailure(), now);
            } else {
                outcome = "retry";
                Duration backoff = props.stepBackoff(attempt);
                log.warn("Workflow {} step '{}' threw (attempt {}), retrying in {}: {}", w.id(), stepName, attempt, backoff, error);
                scheduleRetry(w, ctx, stepName, error, backoff, now);
            }
        }
        metrics.record("oms.workflow.step", Duration.ofNanos(System.nanoTime() - t0), "type", w.type(), "step", stepName, "outcome", outcome);
    }

    /** Keep status and wait fields as they are (so the same wake reason recurs), bump the attempt and back off. */
    private void scheduleRetry(WorkflowInstance w, StepContext ctx, String stepName, String error, Duration backoff, Instant now) {
        int attempt = w.attempt() + 1;
        repo.timeline(w.id(), now, "step", stepName, "retry", Map.of("attempt", attempt, "retryInMs", backoff.toMillis(), "error", StepContext.abbreviate(error)));
        repo.update(with(w).attempt(attempt).lastError(StepContext.abbreviate(error)).nextWakeAt(now.plus(backoff))
            .compensations(ctx.compensations()).updatedAt(now).build());
    }

    private String apply(WorkflowInstance w, WorkflowDefinition d, StepContext ctx, String stepName, Next next, Instant now) {
        Builder b = with(w).vars(ctx.vars()).compensations(ctx.compensations()).attempt(0).lastError(null).updatedAt(now).clearWaits();
        return switch (next) {
            case Next.GoTo g -> {
                repo.timeline(w.id(), now, "step", stepName, "ok", Map.of("next", g.step()));
                repo.update(b.status(WorkflowStatus.RUNNING).currentStep(g.step()).nextWakeAt(now).build());
                yield "ok";
            }
            case Next.WaitSignal ws -> {
                Instant deadline = ws.timeout() == null ? null : now.plus(ws.timeout());
                boolean queued = repo.hasPendingSignal(w.id(), ws.names());
                repo.timeline(w.id(), now, "step", stepName, "ok", Map.of("waitingFor", new ArrayList<>(ws.names()), "timeoutAt", String.valueOf(deadline), "alreadyQueued", queued));
                repo.update(b.status(WorkflowStatus.WAITING_SIGNAL).currentStep(stepName).waitSignals(ws.names()).onSignalStep(ws.onSignalStep())
                    .onTimeoutStep(ws.onTimeoutStep()).timeoutAt(deadline).nextWakeAt(queued ? now : deadline).build());
                yield "wait_signal";
            }
            case Next.Sleep s -> {
                Instant wake = now.plus(s.d());
                repo.timeline(w.id(), now, "step", stepName, "ok", Map.of("sleepMs", s.d().toMillis(), "then", s.thenStep()));
                repo.timeline(w.id(), now, "timer", s.thenStep(), "scheduled", Map.of("fireAt", wake.toString()));
                repo.update(b.status(WorkflowStatus.WAITING_TIMER).currentStep(stepName).onTimeoutStep(s.thenStep()).timeoutAt(wake).nextWakeAt(wake).build());
                yield "sleep";
            }
            case Next.WaitTask wt -> {
                Instant deadline = wt.timeout() == null ? null : now.plus(wt.timeout());
                String signalName = "task:" + wt.taskId();
                boolean queued = repo.hasPendingSignal(w.id(), Set.of(signalName));
                repo.timeline(w.id(), now, "step", stepName, "ok", Map.of("waitingForTask", wt.taskId(), "timeoutAt", String.valueOf(deadline)));
                repo.timeline(w.id(), now, "task", wt.taskId(), "waiting", Map.of("onComplete", wt.onCompleteStep(), "alreadyCompleted", queued));
                repo.update(b.status(WorkflowStatus.WAITING_TASK).currentStep(stepName).waitTaskId(wt.taskId()).onTaskStep(wt.onCompleteStep())
                    .onTimeoutStep(wt.onTimeoutStep()).timeoutAt(deadline).nextWakeAt(queued ? now : deadline).build());
                yield "wait_task";
            }
            case Next.Complete c -> {
                repo.timeline(w.id(), now, "step", stepName, "ok", Map.of("complete", true));
                repo.timeline(w.id(), now, "workflow", w.type(), "completed", Map.of("result", c.result()));
                repo.update(b.status(WorkflowStatus.COMPLETED).currentStep(stepName).result(c.result()).completedAt(now).nextWakeAt(null).build());
                events.append(AGGREGATE, w.id(), new NewEvent("workflow.completed",
                    Map.of("type", w.type(), "businessKey", w.businessKey(), "version", w.version(), "result", c.result()), Actor.system("workflow")));
                yield "completed";
            }
            case Next.Fail f -> {
                repo.timeline(w.id(), now, "step", stepName, "failed", Map.of("reason", String.valueOf(f.reason()), "compensate", f.compensate()));
                fail(w, d, ctx, f.reason(), f.compensate(), now);
                yield "failed";
            }
        };
    }

    // ------------------------------------------------------------------ failure and saga compensation (FR-WF-02)

    /**
     * Terminal failure. With {@code compensate}, in-process undo closures registered during this very execution run
     * first (newest first) for names without a durable handler, then the instance enters COMPENSATING and the worker
     * runs the durable compensation steps in reverse order, one per wake, each with its own retry budget.
     */
    private void fail(WorkflowInstance w, WorkflowDefinition d, StepContext ctx, String reason, boolean compensate, Instant now) {
        String error = StepContext.abbreviate(reason == null ? "failed" : reason);
        Map<String, Object> vars = ctx == null ? w.vars() : ctx.vars();
        List<Map<String, Object>> comps = new ArrayList<>(ctx == null ? w.compensations() : ctx.compensations());
        Builder b = with(w).vars(vars).lastError(error).attempt(0).updatedAt(now).clearWaits();
        events.append(AGGREGATE, w.id(), new NewEvent("workflow.failed",
            Map.of("type", w.type(), "businessKey", w.businessKey(), "version", w.version(), "reason", error, "step", w.currentStep(), "compensating", compensate && !comps.isEmpty()),
            Actor.system("workflow")));
        if (!compensate || comps.isEmpty()) {
            repo.timeline(w.id(), now, "workflow", w.type(), "failed", Map.of("reason", error));
            repo.update(b.status(WorkflowStatus.FAILED).compensations(comps).completedAt(now).nextWakeAt(null).build());
            return;
        }
        if (ctx != null) {
            List<Map<String, Object>> remaining = new ArrayList<>();
            for (int i = comps.size() - 1; i >= 0; i--) {
                Map<String, Object> c = comps.get(i);
                String name = (String) c.get("name");
                Runnable undo = ctx.inMemoryUndos().get(name);
                if (d.compensations().containsKey(name) || undo == null) { remaining.add(0, c); continue; }
                try {
                    undo.run();
                    repo.timeline(w.id(), now, "compensation", name, "ok", Map.of("mode", "in-process"));
                } catch (RuntimeException e) {
                    repo.timeline(w.id(), now, "compensation", name, "failed", Map.of("mode", "in-process", "error", StepContext.abbreviate(e.toString())));
                }
            }
            comps = remaining;
        }
        repo.timeline(w.id(), now, "compensation", w.type(), "started", Map.of("reason", error, "pending", comps.stream().map(c -> c.get("name")).toList()));
        repo.update(b.status(WorkflowStatus.COMPENSATING).currentStep(COMPENSATE_STEP).compensations(comps).nextWakeAt(now).build());
    }

    /** One wake in COMPENSATING runs the newest remaining compensation; an empty list finishes as COMPENSATED. */
    private void runCompensation(WorkflowInstance w, WorkflowDefinition d, Instant now) {
        List<Map<String, Object>> comps = new ArrayList<>(w.compensations());
        if (comps.isEmpty()) {
            repo.timeline(w.id(), now, "workflow", w.type(), "compensated", Map.of("reason", String.valueOf(w.lastError())));
            repo.update(with(w).status(WorkflowStatus.COMPENSATED).completedAt(now).nextWakeAt(null).attempt(0).updatedAt(now).build());
            events.append(AGGREGATE, w.id(), new NewEvent("workflow.compensated",
                Map.of("type", w.type(), "businessKey", w.businessKey(), "version", w.version(), "reason", String.valueOf(w.lastError())), Actor.system("workflow")));
            return;
        }
        Map<String, Object> entry = comps.get(comps.size() - 1);
        String name = (String) entry.get("name");
        Step handler = d.compensations().get(name);
        if (handler == null) {
            repo.timeline(w.id(), now, "compensation", name, "skipped", Map.of("error", "no durable compensation step named '" + name + "' in " + d.type() + " v" + d.version()));
            comps.remove(comps.size() - 1);
            repo.update(with(w).compensations(comps).attempt(0).nextWakeAt(now).updatedAt(now).build());
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = entry.get("vars") instanceof Map<?, ?> m ? (Map<String, Object>) m : w.vars();
        String stepName = COMPENSATE_STEP + ":" + name;
        StepContext ctx = new StepContext(w, d, stepName, null, snapshot, repo, nested, time, metrics, watchdog);
        long t0 = System.nanoTime();
        String outcome;
        try {
            Next next = handler.run(ctx);
            if (next instanceof Next.Fail f) {
                outcome = "failed";
                repo.timeline(w.id(), now, "compensation", name, "failed", Map.of("reason", String.valueOf(f.reason())));
                abortCompensation(w, comps, "compensation '" + name + "' failed: " + f.reason(), now);
            } else {
                outcome = "ok";
                comps.remove(comps.size() - 1);
                repo.timeline(w.id(), now, "compensation", name, "ok", Map.of("remaining", comps.size()));
                repo.update(with(w).compensations(comps).attempt(0).lastError(w.lastError()).nextWakeAt(now).updatedAt(now).build());
            }
        } catch (RetryLater r) {
            outcome = "retry";
            scheduleRetry(w, ctx, stepName, r.getMessage(), r.backoff(), now);
        } catch (Throwable t) {
            int attempt = w.attempt() + 1;
            String error = StepContext.abbreviate(t.toString());
            if (attempt >= props.maxStepAttempts() || t instanceof ActivityFailedException) {
                outcome = "failed";
                repo.timeline(w.id(), now, "compensation", name, "failed", Map.of("attempt", attempt, "error", error));
                abortCompensation(w, comps, "compensation '" + name + "' failed: " + error, now);
            } else {
                outcome = "retry";
                scheduleRetry(w, ctx, stepName, error, props.stepBackoff(attempt), now);
            }
        }
        metrics.record("oms.workflow.step", Duration.ofNanos(System.nanoTime() - t0), "type", w.type(), "step", stepName, "outcome", outcome);
    }

    private void abortCompensation(WorkflowInstance w, List<Map<String, Object>> remaining, String error, Instant now) {
        log.error("Workflow {}: {} ({} compensation(s) left for manual handling)", w.id(), error, remaining.size());
        repo.timeline(w.id(), now, "workflow", w.type(), "failed", Map.of("reason", error, "pendingCompensations", remaining.stream().map(c -> c.get("name")).toList()));
        repo.update(with(w).status(WorkflowStatus.FAILED).lastError(StepContext.abbreviate(error)).compensations(remaining).completedAt(now).nextWakeAt(null).attempt(0).updatedAt(now).build());
    }

    // ------------------------------------------------------------------ instance builder

    static Builder with(WorkflowInstance w) { return new Builder(w); }

    /** Copy-on-write helper for {@link WorkflowInstance}. */
    static final class Builder {
        private String status, currentStep, waitTaskId, onTimeoutStep, onSignalStep, onTaskStep, lastError, cancelReason;
        private Map<String, Object> vars, result;
        private Instant updatedAt, nextWakeAt, timeoutAt, completedAt;
        private Set<String> waitSignals;
        private int attempt;
        private List<Map<String, Object>> compensations;
        private final WorkflowInstance base;

        Builder(WorkflowInstance w) {
            this.base = w;
            status = w.status(); currentStep = w.currentStep(); vars = w.vars(); updatedAt = w.updatedAt(); nextWakeAt = w.nextWakeAt();
            timeoutAt = w.timeoutAt(); waitSignals = w.waitSignals(); waitTaskId = w.waitTaskId(); onTimeoutStep = w.onTimeoutStep();
            onSignalStep = w.onSignalStep(); onTaskStep = w.onTaskStep(); lastError = w.lastError(); attempt = w.attempt();
            compensations = w.compensations(); completedAt = w.completedAt(); result = w.result(); cancelReason = w.cancelReason();
        }

        Builder status(String v) { status = v; return this; }
        Builder currentStep(String v) { currentStep = v; return this; }
        Builder vars(Map<String, Object> v) { vars = new LinkedHashMap<>(v); return this; }
        Builder updatedAt(Instant v) { updatedAt = v; return this; }
        Builder nextWakeAt(Instant v) { nextWakeAt = v; return this; }
        Builder timeoutAt(Instant v) { timeoutAt = v; return this; }
        Builder waitSignals(Set<String> v) { waitSignals = new LinkedHashSet<>(v); return this; }
        Builder waitTaskId(String v) { waitTaskId = v; return this; }
        Builder onTimeoutStep(String v) { onTimeoutStep = v; return this; }
        Builder onSignalStep(String v) { onSignalStep = v; return this; }
        Builder onTaskStep(String v) { onTaskStep = v; return this; }
        Builder lastError(String v) { lastError = v; return this; }
        Builder attempt(int v) { attempt = v; return this; }
        Builder compensations(List<Map<String, Object>> v) { compensations = new ArrayList<>(v); return this; }
        Builder completedAt(Instant v) { completedAt = v; return this; }
        Builder result(Map<String, Object> v) { result = v; return this; }
        Builder cancelReason(String v) { cancelReason = v; return this; }

        Builder clearWaits() {
            timeoutAt = null; waitSignals = Set.of(); waitTaskId = null; onTimeoutStep = null; onSignalStep = null; onTaskStep = null;
            return this;
        }

        WorkflowInstance build() {
            return new WorkflowInstance(base.id(), base.tenantId(), base.type(), base.version(), base.businessKey(), status, currentStep, vars,
                base.startedAt(), updatedAt, nextWakeAt, timeoutAt, waitSignals, waitTaskId, onTimeoutStep, onSignalStep, onTaskStep,
                lastError, attempt, compensations, completedAt, result, cancelReason);
        }
    }
}
