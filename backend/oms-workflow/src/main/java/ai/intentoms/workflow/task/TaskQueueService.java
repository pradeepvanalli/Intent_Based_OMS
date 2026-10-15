package ai.intentoms.workflow.task;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Task queue configuration ({@code task_queue}) (FR-WF-04). Queues referenced before being configured are created with defaults. */
@Service
public class TaskQueueService {
    public static final int DEFAULT_SLA_MINUTES = 240;
    private static final RowMapper<TaskQueue> MAPPER = (rs, i) -> new TaskQueue(rs.getString("name"), rs.getInt("default_sla_minutes"),
        rs.getString("escalation_queue"), Json.readSet(rs.getString("skills")), rs.getString("description"));
    private final JdbcClient jdbc;

    public TaskQueueService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<TaskQueue> list() {
        return jdbc.sql("select name, default_sla_minutes, escalation_queue, skills::text as skills, description from task_queue where tenant_id = :t order by name")
            .param("t", TenantContext.require()).query(MAPPER).list();
    }

    public Optional<TaskQueue> find(String name) {
        return jdbc.sql("select name, default_sla_minutes, escalation_queue, skills::text as skills, description from task_queue where tenant_id = :t and name = :n")
            .param("t", TenantContext.require()).param("n", name).query(MAPPER).optional();
    }

    public TaskQueue get(String name) { return find(name).orElseThrow(() -> new NotFoundException("Queue " + name + " not found")); }

    /** Create or update a queue definition. */
    @Transactional
    public TaskQueue upsert(TaskQueue q) {
        jdbc.sql("""
            insert into task_queue(tenant_id, name, default_sla_minutes, escalation_queue, skills, description)
            values (:t, :n, :sla, :esc, :skills, :desc)
            on conflict (tenant_id, name) do update set default_sla_minutes = excluded.default_sla_minutes, escalation_queue = excluded.escalation_queue,
              skills = excluded.skills, description = excluded.description, updated_at = now()
            """).param("t", TenantContext.require()).param("n", q.name()).param("sla", q.defaultSlaMinutes()).param("esc", q.escalationQueue())
            .param("skills", Jsonb.of(q.skills())).param("desc", q.description()).update();
        return get(q.name());
    }

    /** Return the queue, creating it with the default SLA (240 min) and no escalation on first use. */
    @Transactional
    public TaskQueue ensure(String name) {
        Optional<TaskQueue> existing = find(name);
        if (existing.isPresent()) return existing.get();
        jdbc.sql("insert into task_queue(tenant_id, name, default_sla_minutes, description) values (:t, :n, :sla, :d) on conflict (tenant_id, name) do nothing")
            .param("t", TenantContext.require()).param("n", name).param("sla", DEFAULT_SLA_MINUTES).param("d", "Created on first use").update();
        return get(name);
    }
}
