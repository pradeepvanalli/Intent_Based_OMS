package ai.intentoms.workflow.task;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.engine.WorkflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Escalates overdue human tasks (FR-WF-04): every 30 s, OPEN/CLAIMED tasks past {@code sla_due_at} that have not been
 * escalated move to their queue's {@code escalation_queue} (when configured), get a priority bump, and emit
 * {@code task.escalated}. Tasks whose queue has no escalation target are marked escalated in place so the event
 * fires exactly once. Runs across tenants and binds each row's tenant before touching the ledger.
 */
@Component
public class TaskSweeper {
    private static final Logger log = LoggerFactory.getLogger(TaskSweeper.class);
    private static final int BATCH = 200;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;
    private final WorkflowProperties props;

    public TaskSweeper(JdbcClient jdbc, TransactionTemplate tx, EventStore events, TimeSource time, Metrics metrics, WorkflowProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.time = time;
        this.metrics = metrics;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${oms.workflow.task-sweep-ms:30000}", initialDelayString = "${oms.workflow.task-sweep-ms:30000}")
    public void scheduled() {
        if (!props.enabled()) return;
        try { sweep(); } catch (RuntimeException e) { log.error("Task sweep failed: {}", e.toString()); }
    }

    /** Escalate every overdue task; returns the number escalated. */
    public int sweep() {
        Instant now = time.now();
        Integer n = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.sql("""
                select t.id, t.tenant_id, t.queue, t.priority, t.workflow_id, t.sla_due_at, q.escalation_queue
                from human_task t left join task_queue q on q.tenant_id = t.tenant_id and q.name = t.queue
                where t.status in ('OPEN','CLAIMED') and t.escalated_at is null and t.sla_due_at is not null and t.sla_due_at <= :now
                order by t.sla_due_at limit :n for update of t skip locked
                """).param("now", Timestamp.from(now)).param("n", BATCH).query().listOfRows();
            int count = 0;
            for (Map<String, Object> r : rows) {
                String id = (String) r.get("id");
                String tenant = (String) r.get("tenant_id");
                String from = (String) r.get("queue");
                String to = (String) r.get("escalation_queue");
                int priority = ((Number) r.get("priority")).intValue();
                String target = to == null || to.isBlank() ? from : to;
                TenantContext.runAs(tenant, () -> {
                    if (!target.equals(from)) {
                        jdbc.sql("insert into task_queue(tenant_id, name, default_sla_minutes, description) values (:t, :n, :sla, 'Created by escalation') on conflict (tenant_id, name) do nothing")
                            .param("t", tenant).param("n", target).param("sla", TaskQueueService.DEFAULT_SLA_MINUTES).update();
                    }
                    jdbc.sql("update human_task set queue = :q, escalated_from = :from, escalated_at = :now, priority = :p, updated_at = :now, payload = payload || :ext where id = :id and tenant_id = :t")
                        .param("q", target).param("from", from).param("now", Timestamp.from(now)).param("p", priority + 10)
                        .param("ext", Jsonb.of(Map.of("escalation", Map.of("from", from, "to", target, "at", now.toString()))))
                        .param("id", id).param("t", tenant).update();
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("taskId", id);
                    payload.put("fromQueue", from);
                    payload.put("toQueue", target);
                    payload.put("slaDueAt", ((Timestamp) r.get("sla_due_at")).toInstant().toString());
                    payload.put("priority", priority + 10);
                    if (r.get("workflow_id") != null) payload.put("workflowId", r.get("workflow_id"));
                    events.append(JdbcHumanTaskService.AGGREGATE, id, new NewEvent("task.escalated", payload, Actor.system("task-sweeper")));
                    metrics.count("oms.workflow.task", "queue", from, "op", "escalated");
                });
                count++;
            }
            return count;
        });
        int escalated = n == null ? 0 : n;
        if (escalated > 0) log.info("Escalated {} overdue task(s)", escalated);
        return escalated;
    }
}
