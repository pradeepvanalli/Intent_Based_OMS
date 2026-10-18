package ai.intentoms.orchestration.escalation;

import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.workflow.task.HumanTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cancels escalation tasks that no escalation points at any more. Migration V402 re-pointed duplicate escalations
 * (one order re-escalated every minute by the breach-risk scorer) at the oldest open task for the same decision and
 * subject, which leaves the duplicate tasks orphaned; this closes them through {@link HumanTaskService} so the ledger
 * records why. Idempotent: once nothing is orphaned it does nothing.
 */
@Component
public class EscalationDuplicateCleanup {
    private static final Logger log = LoggerFactory.getLogger(EscalationDuplicateCleanup.class);

    private final JdbcClient jdbc;
    private final HumanTaskService tasks;
    @Value("${oms.orchestration.escalation-cleanup.enabled:true}") private boolean enabled;

    public EscalationDuplicateCleanup(JdbcClient jdbc, HumanTaskService tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void onReady() {
        if (!enabled) return;
        try { runOnce(); } catch (RuntimeException e) { log.warn("Escalation duplicate cleanup failed: {}", e.toString()); }
    }

    /** Returns the number of orphaned escalation tasks cancelled across all tenants. */
    public int runOnce() {
        List<String> tenants = jdbc.sql("select distinct tenant_id from human_task where type = :type and status in ('OPEN','CLAIMED')")
            .param("type", HumanTaskEscalationHandler.TASK_TYPE).query(String.class).list();
        int cancelled = 0;
        for (String tenant : tenants) cancelled += TenantContext.supplyAs(tenant, () -> cleanTenant(tenant));
        if (cancelled > 0) log.info("Cancelled {} orphaned duplicate escalation tasks", cancelled);
        return cancelled;
    }

    private int cleanTenant(String tenant) {
        List<String> orphans = jdbc.sql("""
            select h.id from human_task h
            where h.tenant_id = :t and h.type = :type and h.status in ('OPEN','CLAIMED')
              and not exists (select 1 from decision_escalation e where e.tenant_id = h.tenant_id and e.task_id = h.id)
            """).param("t", tenant).param("type", HumanTaskEscalationHandler.TASK_TYPE).query(String.class).list();
        if (orphans.isEmpty()) return 0;
        var prev = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(tenant)));
        try {
            for (String taskId : orphans) tasks.cancel(taskId, "Duplicate escalation for a decision and subject that already has an open task");
        } finally {
            if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev);
        }
        return orphans.size();
    }
}
