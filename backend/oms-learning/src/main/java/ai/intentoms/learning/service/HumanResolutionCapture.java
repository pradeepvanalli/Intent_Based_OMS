package ai.intentoms.learning.service;

import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final DecisionEscalationRepository escalations;

    public HumanResolutionCapture(HumanTaskService tasks, DecisionRecordRepository decisionRecords, DecisionEscalationRepository escalations) {
        this.tasks = tasks;
        this.decisionRecords = decisionRecords;
        this.escalations = escalations;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("task.completed"); }

    @Override
    public void on(Event e) {
        if (!ESCALATION_TASK_TYPE.equals(e.payload().get("type"))) { onDomainTask(e); return; }
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            @SuppressWarnings("unchecked") Map<String, Object> details = e.payload().get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            HumanTask task = tasks.get(String.valueOf(e.payload().get("taskId")));
            Map<String, Object> taskPayload = task.payload() == null ? Map.of() : task.payload();
            String recordId = str(details.getOrDefault("recordId", taskPayload.get("recordId")));
            String outcome = str(e.payload().get("outcome"));
            String reason = str(e.payload().get("reason"));
            String by = e.actor() == null ? "system:task" : e.actor().ref();
            Object recommended = details.getOrDefault("recommendedValue", taskPayload.get("recommendedValue"));
            Object value = chosenValue(details, outcome, recommended);
            // OC-06: what the person chose (value), what they clicked (outcome) and why (reasonCode). "action" keeps the
            // chosen value for readers of the earlier label shape.
            Map<String, Object> label = new java.util.LinkedHashMap<>();
            label.put("value", value == null ? "" : String.valueOf(value));
            label.put("action", value == null ? "" : String.valueOf(value));
            label.put("outcome", outcome == null ? "" : outcome);
            label.put("reasonCode", reason == null ? "" : reason);
            label.put("resolvedBy", by);
            // E3-N1: a person, an agent (an MCP task_complete) or the system; learners imitate only people.
            label.put("resolvedByType", ai.intentoms.learning.model.HumanResolution.resolverType(by));
            if (recommended != null) label.put("recommendedValue", String.valueOf(recommended));
            // The anchor record plus every re-evaluation linked to the same task: the person answered the question for
            // all of them, and each is a labelled outcome the learners (OC-06, DF-03) should see.
            Set<String> records = new LinkedHashSet<>();
            if (recordId != null) records.add(recordId);
            escalations.forTask(task.id()).forEach(esc -> records.add(esc.recordId()));
            for (String r : records) decisionRecords.label(r, "human_resolution", label);
        } catch (RuntimeException ex) {
            log.warn("Human-resolution capture failed for task.completed event {}: {}", e.id(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    /** Task-payload keys that name the decision record a domain task (return review or denial, exception) is about. */
    private static final List<String> RECORD_KEYS = List.of("decisionRecordId", "classificationRecordId");

    /**
     * OC-06: a domain task that answers a decision's question is a labelled outcome of that decision too: a return's
     * denial review answers its return_risk rule, an exception's resolution follows its exception_classify decision.
     * The record is labelled the way an escalation answer is (value when the person gave one in the decision's terms,
     * otherwise the outcome and reason only), with {@code via} naming the task type. A record that escalated is left to
     * its own escalation task (the return review completes it with the same answer), so a decision has one answer.
     */
    private void onDomainTask(Event e) {
        Object taskId = e.payload().get("taskId");
        if (taskId == null) return;
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            @SuppressWarnings("unchecked") Map<String, Object> details = e.payload().get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            HumanTask task = tasks.get(String.valueOf(taskId));
            Map<String, Object> taskPayload = task.payload() == null ? Map.of() : task.payload();
            Set<String> refs = new LinkedHashSet<>();
            for (String k : RECORD_KEYS) {
                Object v = details.getOrDefault(k, taskPayload.get(k));
                if (v != null && !String.valueOf(v).isBlank()) refs.add(String.valueOf(v));
            }
            if (refs.isEmpty()) return;
            String outcome = str(e.payload().get("outcome"));
            String reason = str(e.payload().get("reason"));
            String by = e.actor() == null ? "system:task" : e.actor().ref();
            for (String ref : refs) {
                decisionRecords.find(ref).filter(r -> r.escalationId() == null).ifPresent(r -> {
                    Object value = chosenValue(details, outcome, r.value());
                    Map<String, Object> label = new java.util.LinkedHashMap<>();
                    label.put("value", value == null ? "" : String.valueOf(value));
                    label.put("action", value == null ? "" : String.valueOf(value));
                    label.put("outcome", outcome == null ? "" : outcome);
                    label.put("reasonCode", reason == null ? "" : reason);
                    label.put("resolvedBy", by);
                    label.put("resolvedByType", ai.intentoms.learning.model.HumanResolution.resolverType(by));
                    if (r.value() != null) label.put("recommendedValue", String.valueOf(r.value()));
                    label.put("via", task.type());
                    label.put("taskId", task.id());
                    decisionRecords.label(r.id(), "human_resolution", label);
                });
            }
        } catch (RuntimeException ex) {
            log.warn("Human-resolution capture failed for {} task event {}: {}", e.payload().get("type"), e.id(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The person's answer, read the same way {@code DecisionEscalationTaskSubscriber} resolves the escalation:
     * {@code details.value} when given, the recommendation for an accepting outcome, otherwise the outcome itself
     * (unless it is just the word "override", which says a value was chosen but not which).
     */
    static Object chosenValue(Map<String, Object> details, String outcome, Object recommended) {
        if (details.get("value") != null && !String.valueOf(details.get("value")).isBlank()) return details.get("value");
        String o = outcome == null ? "" : outcome.toLowerCase(java.util.Locale.ROOT);
        if (ai.intentoms.learning.model.HumanResolution.ACCEPT.contains(o)) return recommended;
        if (o.isBlank() || o.equals("override") || o.equals("overridden")) return null;
        return outcome;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
