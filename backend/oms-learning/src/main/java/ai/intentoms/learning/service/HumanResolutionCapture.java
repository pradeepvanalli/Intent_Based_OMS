package ai.intentoms.learning.service;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * OC-06: captures a human's resolution of an escalation task as a labeled outcome on the decision record itself,
 * reusing the existing {@code labels} column (FR-DEC-10) rather than inventing a parallel store — "what the human
 * did and the reason code" becomes a {@code human_resolution} label the golden-set and calibration tooling can
 * already read. Derives the resolution independently from the same {@code task.completed} event
 * {@link ai.intentoms.orchestration.escalation.DecisionEscalationTaskSubscriber} uses, rather than depending on
 * that subscriber having already run — subscriber dispatch order isn't guaranteed.
 */
@Component
public class HumanResolutionCapture implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(HumanResolutionCapture.class);
    private static final String ESCALATION_TASK_TYPE = "decision_escalation";

    private final HumanTaskService tasks;
    private final DecisionRecordRepository decisionRecords;

    public HumanResolutionCapture(HumanTaskService tasks, DecisionRecordRepository decisionRecords) {
        this.tasks = tasks;
        this.decisionRecords = decisionRecords;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("task.completed"); }

    @Override
    public void on(Event e) {
        if (!ESCALATION_TASK_TYPE.equals(e.payload().get("type"))) return;
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            @SuppressWarnings("unchecked") Map<String, Object> details = e.payload().get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            HumanTask task = tasks.get(String.valueOf(e.payload().get("taskId")));
            Map<String, Object> taskPayload = task.payload() == null ? Map.of() : task.payload();
            String recordId = str(details.getOrDefault("recordId", taskPayload.get("recordId")));
            if (recordId == null) return;
            String outcome = str(e.payload().get("outcome"));
            String reason = str(e.payload().get("reason"));
            String by = e.actor() == null ? "system:task" : e.actor().ref();
            decisionRecords.label(recordId, "human_resolution", Map.of("action", outcome == null ? "" : outcome, "reasonCode", reason == null ? "" : reason, "resolvedBy", by));
        } catch (RuntimeException ex) {
            log.warn("Human-resolution capture failed for task.completed event {}: {}", e.id(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
