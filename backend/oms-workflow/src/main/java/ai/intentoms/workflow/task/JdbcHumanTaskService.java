package ai.intentoms.workflow.task;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC {@link HumanTaskService} (FR-WF-04). Every mutation appends a {@code task.*} ledger event on aggregate
 * {@code task/<id>} in the same transaction; completion of a workflow-attached task signals the workflow (FR-WF-05).
 */
@Service
public class JdbcHumanTaskService implements HumanTaskService {
    private static final Logger log = LoggerFactory.getLogger(JdbcHumanTaskService.class);
    static final String AGGREGATE = "task";
    static final String COLUMNS = """
        id, type, queue, subject_type, subject_id, title, payload::text as payload, status, assignee, sla_due_at, created_at, outcome, outcome_reason,
        completed_by, completed_at, workflow_id, required_skills::text as required_skills, priority
        """;
    static final RowMapper<HumanTask> MAPPER = (rs, i) -> new HumanTask(rs.getString("id"), rs.getString("type"), rs.getString("queue"),
        rs.getString("subject_type"), rs.getString("subject_id"), rs.getString("title"), Json.readMap(rs.getString("payload")), rs.getString("status"),
        rs.getString("assignee"), instant(rs.getTimestamp("sla_due_at")), instant(rs.getTimestamp("created_at")), rs.getString("outcome"),
        rs.getString("outcome_reason"), rs.getString("completed_by"), instant(rs.getTimestamp("completed_at")), rs.getString("workflow_id"),
        Json.readSet(rs.getString("required_skills")), rs.getInt("priority"));

    private final JdbcClient jdbc;
    private final TaskQueueService queues;
    private final WorkflowEngine engine;
    private final EventStore events;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public JdbcHumanTaskService(JdbcClient jdbc, TaskQueueService queues, WorkflowEngine engine, EventStore events, TimeSource time, AuthorizationService authz, Metrics metrics) {
        this.jdbc = jdbc;
        this.queues = queues;
        this.engine = engine;
        this.events = events;
        this.time = time;
        this.authz = authz;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public HumanTask create(TaskRequest r) {
        if (r.type() == null || r.type().isBlank()) throw new ValidationException("task type is required");
        if (r.queue() == null || r.queue().isBlank()) throw new ValidationException("task queue is required");
        if (r.title() == null || r.title().isBlank()) throw new ValidationException("task title is required");
        String tenant = TenantContext.require();
        TaskQueue queue = queues.ensure(r.queue());
        Instant now = time.now();
        Duration sla = r.sla() != null && !r.sla().isNegative() && !r.sla().isZero() ? r.sla() : Duration.ofMinutes(queue.defaultSlaMinutes());
        String id = Ids.prefixed("task");
        jdbc.sql("""
            insert into human_task(id, tenant_id, type, queue, subject_type, subject_id, title, payload, status, sla_due_at, created_at, updated_at, workflow_id, required_skills, priority, created_by)
            values (:id, :t, :type, :queue, :st, :sid, :title, :payload, 'OPEN', :sla, :now, :now, :wf, :skills, :prio, :by)
            """)
            .param("id", id).param("t", tenant).param("type", r.type()).param("queue", r.queue()).param("st", r.subjectType()).param("sid", r.subjectId())
            .param("title", r.title()).param("payload", Jsonb.of(r.payload())).param("sla", Timestamp.from(now.plus(sla))).param("now", Timestamp.from(now))
            .param("wf", r.workflowId()).param("skills", Jsonb.of(r.requiredSkills())).param("prio", r.priority()).param("by", authz.actorRef()).update();
        HumanTask t = get(id);
        events.append(AGGREGATE, id, new NewEvent("task.created", eventPayload(t, Map.of()), actor()));
        metrics.count("oms.workflow.task", "queue", r.queue(), "op", "created");
        return t;
    }

    @Override
    @Transactional
    public HumanTask claim(String taskId, String assignee) {
        HumanTask t = lock(taskId);
        String who = assignee == null || assignee.isBlank() ? principalId() : assignee;
        if (TaskStatus.CLAIMED.equals(t.status()) && !who.equals(t.assignee())) {
            throw new ConflictException("Task " + taskId + " is already claimed by " + t.assignee(), Map.of("assignee", t.assignee()));
        }
        if (!TaskStatus.OPEN.equals(t.status()) && !TaskStatus.CLAIMED.equals(t.status())) {
            throw new ConflictException("Task " + taskId + " is " + t.status() + " and cannot be claimed");
        }
        Instant now = time.now();
        jdbc.sql("update human_task set status = 'CLAIMED', assignee = :a, updated_at = :now where tenant_id = :t and id = :id")
            .param("a", who).param("now", Timestamp.from(now)).param("t", TenantContext.require()).param("id", taskId).update();
        HumanTask out = get(taskId);
        events.append(AGGREGATE, taskId, new NewEvent("task.claimed", eventPayload(out, Map.of("assignee", who)), actor()));
        metrics.count("oms.workflow.task", "queue", out.queue(), "op", "claimed");
        return out;
    }

    @Override
    @Transactional
    public HumanTask complete(String taskId, String outcome, String reason, Map<String, Object> details) {
        if (outcome == null || outcome.isBlank()) throw new ValidationException("outcome is required");
        HumanTask t = lock(taskId);
        if (!t.isOpen()) throw new ConflictException("Task " + taskId + " is " + t.status() + " and cannot be completed");
        Instant now = time.now();
        String by = principalId();
        Map<String, Object> d = details == null ? Map.of() : details;
        jdbc.sql("""
            update human_task set status = 'COMPLETED', outcome = :o, outcome_reason = :r, outcome_details = :d, completed_by = :by, completed_at = :now, updated_at = :now,
              assignee = coalesce(assignee, :by)
            where tenant_id = :t and id = :id
            """)
            .param("o", outcome).param("r", reason).param("d", Jsonb.of(d)).param("by", by).param("now", Timestamp.from(now))
            .param("t", TenantContext.require()).param("id", taskId).update();
        HumanTask out = get(taskId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", outcome);
        payload.put("reason", reason);
        payload.put("details", d);
        events.append(AGGREGATE, taskId, new NewEvent("task.completed", eventPayload(out, payload), actor()));
        if (out.workflowId() != null) {
            Map<String, Object> signal = new LinkedHashMap<>(payload);
            signal.put("taskId", taskId);
            signal.put("completedBy", by);
            try {
                engine.signalById(out.workflowId(), "task:" + taskId, signal, "task:" + taskId + ":completed");
            } catch (NotFoundException e) {
                log.warn("Task {} completed but workflow {} no longer exists; signal dropped", taskId, out.workflowId());
            }
        }
        boolean late = out.slaDueAt() != null && now.isAfter(out.slaDueAt());
        metrics.count("oms.workflow.task", "queue", out.queue(), "op", late ? "completed_late" : "completed");
        return out;
    }

    @Override
    @Transactional
    public HumanTask refreshPayload(String taskId, Map<String, Object> patch) {
        HumanTask t = lock(taskId);
        if (!t.isOpen()) throw new ConflictException("Task " + taskId + " is " + t.status() + " and cannot be refreshed");
        Instant now = time.now();
        jdbc.sql("update human_task set payload = coalesce(payload, '{}'::jsonb) || :p, updated_at = :now where tenant_id = :t and id = :id")
            .param("p", Jsonb.of(patch)).param("now", Timestamp.from(now)).param("t", TenantContext.require()).param("id", taskId).update();
        HumanTask out = get(taskId);
        events.append(AGGREGATE, taskId, new NewEvent("task.refreshed", eventPayload(out, Map.of("keys", List.copyOf(patch.keySet()))), actor()));
        return out;
    }

    @Override
    @Transactional
    public HumanTask cancel(String taskId, String reason) {
        HumanTask t = lock(taskId);
        if (!t.isOpen()) throw new ConflictException("Task " + taskId + " is " + t.status() + " and cannot be cancelled");
        Instant now = time.now();
        jdbc.sql("update human_task set status = 'CANCELLED', outcome_reason = :r, completed_by = :by, completed_at = :now, updated_at = :now where tenant_id = :t and id = :id")
            .param("r", reason).param("by", principalId()).param("now", Timestamp.from(now)).param("t", TenantContext.require()).param("id", taskId).update();
        HumanTask out = get(taskId);
        events.append(AGGREGATE, taskId, new NewEvent("task.cancelled", eventPayload(out, Map.of("reason", String.valueOf(reason))), actor()));
        metrics.count("oms.workflow.task", "queue", out.queue(), "op", "cancelled");
        return out;
    }

    @Override
    public Page<HumanTask> list(TaskQuery q) {
        StringBuilder where = new StringBuilder(" where tenant_id = :t");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", TenantContext.require());
        filter(where, params, "queue", q.queue());
        filter(where, params, "status", q.status());
        filter(where, params, "assignee", q.assignee());
        filter(where, params, "subject_type", q.subjectType());
        filter(where, params, "subject_id", q.subjectId());
        filter(where, params, "workflow_id", q.workflowId());
        long total = jdbc.sql("select count(*) from human_task" + where).params(params).query(Long.class).single();
        List<HumanTask> items = jdbc.sql("select " + COLUMNS + " from human_task" + where + " order by priority desc, sla_due_at asc nulls last, created_at asc offset :off limit :lim")
            .params(params).param("off", q.offset()).param("lim", q.limit()).query(MAPPER).list();
        return Page.of(items, total, q.offset(), q.limit());
    }

    @Override
    public Map<String, long[]> queueCounts() {
        Map<String, long[]> out = new java.util.TreeMap<>();
        jdbc.sql("""
            select queue,
                   count(*) filter (where status = 'OPEN') as open_count,
                   count(*) filter (where status = 'CLAIMED') as claimed_count,
                   count(*) filter (where sla_due_at < now()) as overdue_count
            from human_task where tenant_id = :t and status in ('OPEN', 'CLAIMED') group by queue
            """).param("t", TenantContext.require()).query().listOfRows().forEach(r -> out.put((String) r.get("queue"),
                new long[]{((Number) r.get("open_count")).longValue(), ((Number) r.get("claimed_count")).longValue(), ((Number) r.get("overdue_count")).longValue()}));
        return out;
    }

    @Override
    public Page<HumanTask> listMine(java.util.Set<String> queues, String assignee, String status, int offset, int limit) {
        int off = Math.max(0, offset);
        int lim = limit <= 0 || limit > 500 ? 50 : limit;
        StringBuilder where = new StringBuilder(" where tenant_id = :t");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", TenantContext.require());
        filter(where, params, "status", status);
        if (queues != null) {
            params.put("me", assignee == null ? "" : assignee);
            if (queues.isEmpty()) where.append(" and assignee = :me");
            else { where.append(" and (queue in (:queues) or assignee = :me)"); params.put("queues", List.copyOf(queues)); }
        }
        long total = jdbc.sql("select count(*) from human_task" + where).params(params).query(Long.class).single();
        List<HumanTask> items = jdbc.sql("select " + COLUMNS + " from human_task" + where + " order by priority desc, sla_due_at asc nulls last, created_at asc offset :off limit :lim")
            .params(params).param("off", off).param("lim", lim).query(MAPPER).list();
        return Page.of(items, total, off, lim);
    }

    /** Equality filter; a comma-separated value ("OPEN,CLAIMED") matches any of its entries. */
    private static void filter(StringBuilder where, Map<String, Object> params, String column, String value) {
        if (value == null || value.isBlank()) return;
        List<String> values = java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
        if (values.size() > 1) {
            where.append(" and ").append(column).append(" in (:").append(column).append(")");
            params.put(column, values);
        } else {
            where.append(" and ").append(column).append(" = :").append(column);
            params.put(column, values.isEmpty() ? value : values.get(0));
        }
    }

    @Override
    public HumanTask get(String id) {
        return find(id).orElseThrow(() -> new NotFoundException("Task " + id + " not found"));
    }

    private Optional<HumanTask> find(String id) {
        return jdbc.sql("select " + COLUMNS + " from human_task where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    private HumanTask lock(String id) {
        return jdbc.sql("select " + COLUMNS + " from human_task where tenant_id = :t and id = :id for update").param("t", TenantContext.require()).param("id", id)
            .query(MAPPER).optional().orElseThrow(() -> new NotFoundException("Task " + id + " not found"));
    }

    private String principalId() { return authz.currentPrincipal().map(Principal::id).orElse("system"); }

    private Actor actor() { return Actor.parse(authz.actorRef()); }

    static Map<String, Object> eventPayload(HumanTask t, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.id());
        m.put("type", t.type());
        m.put("queue", t.queue());
        m.put("status", t.status());
        if (t.subjectType() != null) m.put("subjectType", t.subjectType());
        if (t.subjectId() != null) m.put("subjectId", t.subjectId());
        if (t.workflowId() != null) m.put("workflowId", t.workflowId());
        if (t.slaDueAt() != null) m.put("slaDueAt", t.slaDueAt().toString());
        m.put("priority", t.priority());
        extra.forEach((k, v) -> { if (v != null) m.put(k, v); });
        return m;
    }

    static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
