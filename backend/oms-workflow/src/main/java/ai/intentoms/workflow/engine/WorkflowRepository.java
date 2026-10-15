package ai.intentoms.workflow.engine;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.TimelineEntry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written SQL for the engine tables ({@code workflow_instance}, {@code workflow_activity}, {@code workflow_signal},
 * {@code workflow_timeline}). All instance reads are tenant-scoped except the worker's cross-tenant lock query, which
 * binds the row's tenant before any business code runs.
 */
@Repository
public class WorkflowRepository {
    private static final String INSTANCE_COLUMNS = """
        id, tenant_id, type, version, business_key, status, current_step, vars::text as vars, started_at, updated_at, next_wake_at, timeout_at,
        wait_signals::text as wait_signals, wait_task_id, on_timeout_step, on_signal_step, on_task_step, last_error, attempt,
        compensations::text as compensations, completed_at, result::text as result, cancel_reason
        """;

    private static final RowMapper<WorkflowInstance> INSTANCE = (rs, i) -> new WorkflowInstance(
        rs.getString("id"), rs.getString("tenant_id"), rs.getString("type"), rs.getInt("version"), rs.getString("business_key"),
        rs.getString("status"), rs.getString("current_step"), Json.readMap(rs.getString("vars")),
        instant(rs, "started_at"), instant(rs, "updated_at"), instant(rs, "next_wake_at"), instant(rs, "timeout_at"),
        new LinkedHashSet<>(Json.readSet(rs.getString("wait_signals"))), rs.getString("wait_task_id"), rs.getString("on_timeout_step"),
        rs.getString("on_signal_step"), rs.getString("on_task_step"), rs.getString("last_error"), rs.getInt("attempt"),
        readCompensations(rs.getString("compensations")), instant(rs, "completed_at"),
        rs.getString("result") == null ? null : Json.readMap(rs.getString("result")), rs.getString("cancel_reason"));

    /** A persisted signal row; {@code id} is the queue sequence. */
    public record SignalRow(long id, Signal signal) {}

    /** A persisted activity row (attempt log and result cache). */
    public record ActivityRow(String id, String step, String name, int attempt, String status, Object result, String error) {}

    private static final RowMapper<SignalRow> SIGNAL = (rs, i) -> new SignalRow(rs.getLong("id"),
        new Signal(rs.getString("name"), Json.readMap(rs.getString("payload")), rs.getString("idempotency_key"), instant(rs, "received_at")));

    private static final RowMapper<ActivityRow> ACTIVITY = (rs, i) -> new ActivityRow(rs.getString("id"), rs.getString("step"), rs.getString("name"),
        rs.getInt("attempt"), rs.getString("status"), rs.getString("result") == null ? null : Json.read(rs.getString("result"), Object.class), rs.getString("error"));

    private static final RowMapper<TimelineEntry> TIMELINE = (rs, i) -> new TimelineEntry(instant(rs, "at"), rs.getString("kind"), rs.getString("name"),
        rs.getString("status"), Json.readMap(rs.getString("details")));

    private final JdbcClient jdbc;

    public WorkflowRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---------------------------------------------------------------- instances

    /** Insert a new instance; returns false when (tenant, type, business_key) already exists. */
    public boolean insertIfAbsent(WorkflowInstance w) {
        int n = jdbc.sql("""
            insert into workflow_instance(id, tenant_id, type, version, business_key, status, current_step, vars, started_at, updated_at, next_wake_at, attempt)
            values (:id, :tenant, :type, :version, :key, :status, :step, :vars, :started, :updated, :wake, 0)
            on conflict (tenant_id, type, business_key) do nothing
            """)
            .param("id", w.id()).param("tenant", w.tenantId()).param("type", w.type()).param("version", w.version()).param("key", w.businessKey())
            .param("status", w.status()).param("step", w.currentStep()).param("vars", Jsonb.of(w.vars()))
            .param("started", ts(w.startedAt())).param("updated", ts(w.updatedAt())).param("wake", ts(w.nextWakeAt()))
            .update();
        return n == 1;
    }

    public Optional<String> findId(String tenant, String type, String businessKey) {
        return jdbc.sql("select id from workflow_instance where tenant_id = :t and type = :type and business_key = :k")
            .param("t", tenant).param("type", type).param("k", businessKey).query(String.class).optional();
    }

    public Optional<WorkflowInstance> find(String tenant, String id) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from workflow_instance where tenant_id = :t and id = :id")
            .param("t", tenant).param("id", id).query(INSTANCE).optional();
    }

    public Optional<WorkflowInstance> findByKey(String tenant, String type, String businessKey) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from workflow_instance where tenant_id = :t and type = :type and business_key = :k")
            .param("t", tenant).param("type", type).param("k", businessKey).query(INSTANCE).optional();
    }

    /** Lock the instance row for the rest of the transaction (used by cancel/retryNow so they serialise with a running step). */
    public Optional<WorkflowInstance> lock(String tenant, String id) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from workflow_instance where tenant_id = :t and id = :id for update")
            .param("t", tenant).param("id", id).query(INSTANCE).optional();
    }

    /**
     * Worker pick-up: the earliest due active instance across tenants, locked with SKIP LOCKED so N workers (and N
     * app instances) never run the same instance concurrently (FR-WF-01).
     */
    public Optional<WorkflowInstance> lockNextDue(Instant now) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + """
             from workflow_instance
             where status in ('RUNNING','WAITING_SIGNAL','WAITING_TIMER','WAITING_TASK','COMPENSATING') and next_wake_at is not null and next_wake_at <= :now
             order by next_wake_at limit 1 for update skip locked
            """).param("now", ts(now)).query(INSTANCE).optional();
    }

    public long countDue(Instant now) {
        return jdbc.sql("""
            select count(*) from workflow_instance
            where status in ('RUNNING','WAITING_SIGNAL','WAITING_TIMER','WAITING_TASK','COMPENSATING') and next_wake_at is not null and next_wake_at <= :now
            """).param("now", ts(now)).query(Long.class).single();
    }

    /** Persist every mutable column of the instance. */
    public void update(WorkflowInstance w) {
        jdbc.sql("""
            update workflow_instance set status = :status, current_step = :step, vars = :vars, updated_at = :updated, next_wake_at = :wake, timeout_at = :timeout,
              wait_signals = :signals, wait_task_id = :task, on_timeout_step = :onTimeout, on_signal_step = :onSignal, on_task_step = :onTask,
              last_error = :err, attempt = :attempt, compensations = :comps, completed_at = :completed, result = :result, cancel_reason = :cancel
            where id = :id and tenant_id = :tenant
            """)
            .param("status", w.status()).param("step", w.currentStep()).param("vars", Jsonb.of(w.vars())).param("updated", ts(w.updatedAt()))
            .param("wake", ts(w.nextWakeAt())).param("timeout", ts(w.timeoutAt())).param("signals", Jsonb.of(w.waitSignals()))
            .param("task", w.waitTaskId()).param("onTimeout", w.onTimeoutStep()).param("onSignal", w.onSignalStep()).param("onTask", w.onTaskStep())
            .param("err", w.lastError()).param("attempt", w.attempt()).param("comps", Jsonb.of(w.compensations())).param("completed", ts(w.completedAt()))
            .param("result", Jsonb.of(w.result())).param("cancel", w.cancelReason()).param("id", w.id()).param("tenant", w.tenantId())
            .update();
    }

    /** Minimal retry bookkeeping used when a step's own transaction had to be rolled back. */
    public void recordRetry(String id, int attempt, String error, Instant nextWake, String status, Instant now) {
        jdbc.sql("update workflow_instance set attempt = :attempt, last_error = :err, next_wake_at = :wake, status = :status, updated_at = :now, completed_at = case when :status in ('FAILED') then :now else completed_at end where id = :id")
            .param("attempt", attempt).param("err", error).param("wake", ts(nextWake)).param("status", status).param("now", ts(now)).param("id", id).update();
    }

    /**
     * Wake the instance if the signal is relevant right now: RUNNING instances, instances waiting for this signal
     * name, or instances waiting for the task this signal completes. Returns true when a wake was scheduled.
     */
    public boolean wakeForSignal(String tenant, String id, String name, Instant now) {
        return jdbc.sql("""
            update workflow_instance set next_wake_at = least(coalesce(next_wake_at, :now), :now), updated_at = :now
            where id = :id and tenant_id = :t and (
              status = 'RUNNING'
              or (status = 'WAITING_SIGNAL' and jsonb_exists(wait_signals, :name))
              or (status = 'WAITING_TASK' and wait_task_id is not null and :name = 'task:' || wait_task_id))
            """).param("now", ts(now)).param("id", id).param("t", tenant).param("name", name).update() == 1;
    }

    // ---------------------------------------------------------------- signals

    /** Enqueue a signal; returns false when the idempotency key was already seen for this instance (FR-WF-05). */
    public boolean insertSignal(String workflowId, String name, Map<String, Object> payload, String idempotencyKey, Instant now) {
        try {
            int n = jdbc.sql("""
                insert into workflow_signal(workflow_id, name, payload, idempotency_key, received_at) values (:wf, :name, :payload, :key, :now)
                on conflict (workflow_id, idempotency_key) do nothing
                """).param("wf", workflowId).param("name", name).param("payload", Jsonb.of(payload == null ? Map.of() : payload))
                .param("key", idempotencyKey).param("now", ts(now)).update();
            return n == 1;
        } catch (DuplicateKeyException dup) {
            return false;
        }
    }

    /** Oldest unconsumed signal whose name is in {@code names}, in arrival order. */
    public Optional<SignalRow> nextPendingSignal(String workflowId, Collection<String> names) {
        if (names == null || names.isEmpty()) return Optional.empty();
        return jdbc.sql("select id, name, payload::text as payload, idempotency_key, received_at from workflow_signal where workflow_id = :wf and consumed_at is null and name in (:names) order by id limit 1")
            .param("wf", workflowId).param("names", new ArrayList<>(names)).query(SIGNAL).optional();
    }

    public boolean hasPendingSignal(String workflowId, Collection<String> names) {
        return nextPendingSignal(workflowId, names).isPresent();
    }

    public void consumeSignal(long signalId, Instant now) {
        jdbc.sql("update workflow_signal set consumed_at = :now where id = :id").param("now", ts(now)).param("id", signalId).update();
    }

    /** Names of signals still queued for the instance (for the "next expected event" synthesis, FR-WF-06). */
    public List<String> pendingSignalNames(String workflowId) {
        return jdbc.sql("select name from workflow_signal where workflow_id = :wf and consumed_at is null order by id").param("wf", workflowId).query(String.class).list();
    }

    // ---------------------------------------------------------------- activities

    public Optional<ActivityRow> findActivity(String workflowId, String step, String name) {
        return jdbc.sql("select id, step, name, attempt, status, result::text as result, error from workflow_activity where workflow_id = :wf and step = :step and name = :name")
            .param("wf", workflowId).param("step", step).param("name", name).query(ACTIVITY).optional();
    }

    /**
     * Record an attempt; the single row per (workflow, step, name) is created on the first attempt and updated after.
     * The result is serialised explicitly so scalar results (a String id, a number) are stored as valid JSON values.
     */
    public void upsertActivity(String id, String workflowId, String step, String name, int attempt, String status, Object result, String error, Instant started, Instant finished) {
        jdbc.sql("""
            insert into workflow_activity(id, workflow_id, step, name, attempt, status, result, error, started_at, finished_at)
            values (:id, :wf, :step, :name, :attempt, :status, :result, :error, :started, :finished)
            on conflict (workflow_id, step, name) do update set attempt = excluded.attempt, status = excluded.status, result = excluded.result,
              error = excluded.error, started_at = excluded.started_at, finished_at = excluded.finished_at
            """)
            .param("id", id).param("wf", workflowId).param("step", step).param("name", name).param("attempt", attempt).param("status", status)
            .param("result", Jsonb.of(Json.write(result))).param("error", error).param("started", ts(started)).param("finished", ts(finished)).update();
    }

    // ---------------------------------------------------------------- timeline

    public void timeline(String workflowId, Instant at, String kind, String name, String status, Map<String, Object> details) {
        jdbc.sql("insert into workflow_timeline(workflow_id, at, kind, name, status, details) values (:wf, :at, :kind, :name, :status, :details)")
            .param("wf", workflowId).param("at", ts(at)).param("kind", kind).param("name", name).param("status", status)
            .param("details", Jsonb.of(details == null ? Map.of() : details)).update();
    }

    public List<TimelineEntry> timeline(String workflowId) {
        return jdbc.sql("select at, kind, name, status, details::text as details from workflow_timeline where workflow_id = :wf order by id")
            .param("wf", workflowId).query(TIMELINE).list();
    }

    // ---------------------------------------------------------------- human task bridge

    /** Mark a human task EXPIRED when the workflow stops waiting for it (timeout or cancel). Returns true when a row changed. */
    public boolean expireTask(String tenant, String taskId, Instant now) {
        return jdbc.sql("update human_task set status = 'EXPIRED', updated_at = :now where tenant_id = :t and id = :id and status in ('OPEN','CLAIMED')")
            .param("now", ts(now)).param("t", tenant).param("id", taskId).update() == 1;
    }

    // ---------------------------------------------------------------- helpers

    static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readCompensations(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.readList(json)) out.add(new LinkedHashMap<>((Map<String, Object>) o));
        return out;
    }

}
