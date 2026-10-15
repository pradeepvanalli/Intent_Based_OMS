package ai.intentoms.workflow.engine;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.model.TimelineEntry;
import ai.intentoms.workflow.model.WorkflowDefinition;
import ai.intentoms.workflow.model.WorkflowEngine;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link WorkflowEngine} (FR-WF-01, FR-WF-05, FR-WF-06, FR-WF-08). Instances are rows
 * in {@code workflow_instance}; steps are executed by {@link WorkflowWorker} through {@link StepRunner}. Every
 * mutation appends a {@code workflow.*} ledger event on aggregate {@code workflow/<id>} in the same transaction.
 */
@Service
public class JdbcWorkflowEngine implements WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowEngine.class);
    private final WorkflowRepository repo;
    private final WorkflowRegistry registry;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;
    private final AuthorizationService authz;

    public JdbcWorkflowEngine(WorkflowRepository repo, WorkflowRegistry registry, EventStore events, TimeSource time, Metrics metrics, AuthorizationService authz) {
        this.repo = repo;
        this.registry = registry;
        this.events = events;
        this.time = time;
        this.metrics = metrics;
        this.authz = authz;
    }

    @Override
    @Transactional
    public String start(String type, String businessKey, Map<String, Object> input) {
        if (type == null || type.isBlank()) throw new ValidationException("workflow type is required");
        if (businessKey == null || businessKey.isBlank()) throw new ValidationException("businessKey is required");
        String tenant = TenantContext.require();
        WorkflowDefinition def = registry.latest(type);
        Instant now = time.now();
        Map<String, Object> vars = new LinkedHashMap<>(input == null ? Map.of() : input);
        WorkflowInstance w = new WorkflowInstance(Ids.prefixed("wf"), tenant, type, def.version(), businessKey, WorkflowStatus.RUNNING, def.initialStep(), vars,
            now, now, now, null, java.util.Set.of(), null, null, null, null, null, 0, List.of(), null, null, null);
        if (!repo.insertIfAbsent(w)) {
            String existing = repo.findId(tenant, type, businessKey).orElseThrow(() -> new ConflictException("Workflow " + type + "/" + businessKey + " is being created concurrently"));
            log.debug("start({}, {}) is idempotent; returning existing {}", type, businessKey, existing);
            return existing;
        }
        repo.timeline(w.id(), now, "workflow", type, "started", Map.of("version", def.version(), "businessKey", businessKey, "initialStep", def.initialStep()));
        events.append(StepRunner.AGGREGATE, w.id(), new NewEvent("workflow.started",
            Map.of("type", type, "businessKey", businessKey, "version", def.version(), "initialStep", def.initialStep()), actor()));
        metrics.count("oms.workflow.started", "type", type);
        return w.id();
    }

    @Override
    @Transactional
    public void signal(String type, String businessKey, String name, Map<String, Object> payload, String idempotencyKey) {
        String tenant = TenantContext.require();
        String id = repo.findId(tenant, type, businessKey).orElseThrow(() -> new NotFoundException("No workflow " + type + " for business key " + businessKey));
        signalById(id, name, payload, idempotencyKey);
    }

    @Override
    @Transactional
    public void signalById(String workflowId, String name, Map<String, Object> payload, String idempotencyKey) {
        if (name == null || name.isBlank()) throw new ValidationException("signal name is required");
        String tenant = TenantContext.require();
        WorkflowInstance w = repo.find(tenant, workflowId).orElseThrow(() -> new NotFoundException("Workflow " + workflowId + " not found"));
        Instant now = time.now();
        Map<String, Object> p = payload == null ? Map.of() : payload;
        if (!repo.insertSignal(workflowId, name, p, idempotencyKey, now)) {
            log.debug("Duplicate signal {} / {} for workflow {} ignored", name, idempotencyKey, workflowId);
            metrics.count("oms.workflow.signal", "type", w.type(), "name", name, "outcome", "duplicate");
            return;
        }
        boolean woke = !WorkflowStatus.isTerminal(w.status()) && repo.wakeForSignal(tenant, workflowId, name, now);
        String status = WorkflowStatus.isTerminal(w.status()) ? "received_terminal" : woke ? "received" : "queued";
        repo.timeline(workflowId, now, "signal", name, status, Map.of("payload", p, "idempotencyKey", String.valueOf(idempotencyKey)));
        events.append(StepRunner.AGGREGATE, workflowId, new NewEvent("workflow.signalled",
            Map.of("type", w.type(), "businessKey", w.businessKey(), "name", name, "payload", p, "idempotencyKey", String.valueOf(idempotencyKey), "woke", woke), actor()));
        metrics.count("oms.workflow.signal", "type", w.type(), "name", name, "outcome", status);
    }

    @Override
    public Optional<WorkflowView> find(String type, String businessKey) {
        return repo.findByKey(TenantContext.require(), type, businessKey).map(WorkflowInstance::toView);
    }

    @Override
    public WorkflowView get(String workflowId) {
        return instance(workflowId).toView();
    }

    @Override
    public List<TimelineEntry> timeline(String workflowId) {
        WorkflowInstance w = instance(workflowId);
        List<TimelineEntry> out = new ArrayList<>(repo.timeline(workflowId));
        Instant now = time.now();
        switch (w.status()) {
            case WorkflowStatus.WAITING_TIMER -> out.add(new TimelineEntry(now, "timer", w.onTimeoutStep(), "pending",
                details("fireAt", w.timeoutAt(), "nextWakeAt", w.nextWakeAt(), "then", w.onTimeoutStep())));
            case WorkflowStatus.WAITING_SIGNAL -> {
                if (w.timeoutAt() != null) out.add(new TimelineEntry(now, "timer", w.onTimeoutStep(), "pending", details("fireAt", w.timeoutAt(), "onTimeout", w.onTimeoutStep())));
                out.add(new TimelineEntry(now, "signal", String.join("|", w.waitSignals()), "expected",
                    details("names", new ArrayList<>(w.waitSignals()), "onSignal", w.onSignalStep() != null ? w.onSignalStep() : w.currentStep(), "queued", repo.pendingSignalNames(workflowId))));
            }
            case WorkflowStatus.WAITING_TASK -> {
                if (w.timeoutAt() != null) out.add(new TimelineEntry(now, "timer", w.onTimeoutStep(), "pending", details("fireAt", w.timeoutAt(), "onTimeout", w.onTimeoutStep())));
                out.add(new TimelineEntry(now, "task", w.waitTaskId(), "expected", details("taskId", w.waitTaskId(), "onComplete", w.onTaskStep())));
            }
            case WorkflowStatus.RUNNING, WorkflowStatus.COMPENSATING -> out.add(new TimelineEntry(now, "step", w.currentStep(), "pending",
                details("nextWakeAt", w.nextWakeAt(), "attempt", w.attempt(), "lastError", w.lastError())));
            default -> { }
        }
        return out;
    }

    @Override
    @Transactional
    public void cancel(String workflowId, String reason) {
        String tenant = TenantContext.require();
        WorkflowInstance w = repo.lock(tenant, workflowId).orElseThrow(() -> new NotFoundException("Workflow " + workflowId + " not found"));
        if (WorkflowStatus.isTerminal(w.status())) throw new ConflictException("Workflow " + workflowId + " is already " + w.status());
        Instant now = time.now();
        String why = reason == null || reason.isBlank() ? "cancelled" : reason;
        if (WorkflowStatus.WAITING_TASK.equals(w.status()) && w.waitTaskId() != null && repo.expireTask(tenant, w.waitTaskId(), now)) {
            repo.timeline(workflowId, now, "task", w.waitTaskId(), "expired", Map.of("reason", why));
            events.append("task", w.waitTaskId(), new NewEvent("task.expired", Map.of("taskId", w.waitTaskId(), "workflowId", workflowId, "reason", why), actor()));
        }
        repo.timeline(workflowId, now, "workflow", w.type(), "cancelled", Map.of("reason", why, "step", w.currentStep()));
        repo.update(StepRunner.with(w).status(WorkflowStatus.CANCELLED).cancelReason(why).completedAt(now).nextWakeAt(null).updatedAt(now).clearWaits().build());
        events.append(StepRunner.AGGREGATE, workflowId, new NewEvent("workflow.cancelled",
            Map.of("type", w.type(), "businessKey", w.businessKey(), "reason", why, "step", w.currentStep()), actor()));
        metrics.count("oms.workflow.cancelled", "type", w.type());
    }

    @Override
    @Transactional
    public void retryNow(String workflowId) {
        String tenant = TenantContext.require();
        WorkflowInstance w = repo.lock(tenant, workflowId).orElseThrow(() -> new NotFoundException("Workflow " + workflowId + " not found"));
        Instant now = time.now();
        StepRunner.Builder b = StepRunner.with(w).nextWakeAt(now).updatedAt(now);
        switch (w.status()) {
            case WorkflowStatus.FAILED -> b.status(w.currentStep().startsWith(StepRunner.COMPENSATE_STEP) ? WorkflowStatus.COMPENSATING : WorkflowStatus.RUNNING).attempt(0).completedAt(null);
            case WorkflowStatus.WAITING_TIMER -> b.timeoutAt(now);
            case WorkflowStatus.RUNNING, WorkflowStatus.COMPENSATING, WorkflowStatus.WAITING_SIGNAL, WorkflowStatus.WAITING_TASK -> { }
            default -> throw new ConflictException("Workflow " + workflowId + " is " + w.status() + " and cannot be retried");
        }
        repo.timeline(workflowId, now, "log", "retryNow", "requested", Map.of("by", authz.actorRef(), "status", w.status(), "step", w.currentStep()));
        repo.update(b.build());
        metrics.count("oms.workflow.retry_now", "type", w.type());
    }

    private WorkflowInstance instance(String workflowId) {
        return repo.find(TenantContext.require(), workflowId).orElseThrow(() -> new NotFoundException("Workflow " + workflowId + " not found"));
    }

    private Actor actor() { return Actor.parse(authz.actorRef()); }

    private static Map<String, Object> details(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v != null) m.put((String) kv[i], v instanceof Instant t ? t.toString() : v);
        }
        return m;
    }
}
