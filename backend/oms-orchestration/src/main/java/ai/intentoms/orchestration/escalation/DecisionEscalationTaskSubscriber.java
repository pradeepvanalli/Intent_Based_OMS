package ai.intentoms.orchestration.escalation;

import ai.intentoms.decision.model.DecisionEscalation;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Closes the L3 loop (GR-13): when a {@code decision_escalation} task completes, the {@code decision_escalation} row is
 * resolved with the human's value and, when that value differs from the model's recommendation, the decision record is
 * overridden with the stated reason ({@code decision.overridden}). Outcomes {@code accept | approved | agree | confirm}
 * keep the recommendation; any other outcome (or {@code details.value}) is the human's value. Idempotent: an already
 * resolved escalation is left alone.
 */
@Component
public class DecisionEscalationTaskSubscriber implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(DecisionEscalationTaskSubscriber.class);
    private static final Set<String> ACCEPT = Set.of("accept", "accepted", "approved", "approve", "agree", "confirm", "confirmed", "ok", "yes");
    private final DecisionEscalationRepository escalations;
    private final DecisionService decisions;
    private final HumanTaskService tasks;
    private final TimeSource time;

    public DecisionEscalationTaskSubscriber(DecisionEscalationRepository escalations, DecisionService decisions, HumanTaskService tasks, TimeSource time) {
        this.escalations = escalations;
        this.decisions = decisions;
        this.tasks = tasks;
        this.time = time;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("task.completed"); }

    @Override
    public void on(Event e) {
        if (!HumanTaskEscalationHandler.TASK_TYPE.equals(e.payload().get("type"))) return;
        @SuppressWarnings("unchecked") Map<String, Object> details = e.payload().get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        HumanTask task = tasks.get(String.valueOf(e.payload().get("taskId")));
        Map<String, Object> taskPayload = task.payload() == null ? Map.of() : task.payload();
        String escalationId = str(details.getOrDefault("escalationId", taskPayload.get("escalationId")));
        String recordId = str(details.getOrDefault("recordId", taskPayload.get("recordId")));
        if (escalationId == null || recordId == null) {
            log.warn("decision_escalation task {} completed without escalationId/recordId; nothing resolved", task.id());
            return;
        }
        Optional<DecisionEscalation> esc = escalations.find(escalationId);
        if (esc.isEmpty() || !"OPEN".equals(esc.get().status())) return;
        String outcome = str(e.payload().get("outcome"));
        Object recommended = details.getOrDefault("recommendedValue", taskPayload.get("recommendedValue"));
        Object value = details.containsKey("value") ? details.get("value") : ACCEPT.contains(outcome == null ? "" : outcome.toLowerCase(Locale.ROOT)) ? recommended : outcome;
        String by = e.actor() == null ? "system:task" : e.actor().ref();
        escalations.resolve(escalationId, value, by, time.now());
        // Later re-evaluations of the same question were linked to this task instead of opening their own
        // (HumanTaskEscalationHandler); the person's answer resolves them too.
        for (DecisionEscalation linked : escalations.unresolvedForTask(task.id())) escalations.resolve(linked.id(), value, by, time.now());
        boolean override = value != null && (recommended == null || !String.valueOf(value).equals(String.valueOf(recommended)));
        if (!override) return;
        String reason = str(e.payload().get("reason"));
        runAsSystem(e.tenantId(), () -> decisions.override(recordId, value, reason == null || reason.isBlank() ? "Human decision on escalation " + escalationId + " by " + by : reason));
        log.info("Escalation {} resolved by {} with override {} → {} on record {}", escalationId, by, recommended, value, recordId);
    }

    /** The relay thread carries no principal; the override API requires {@code decision.override}, which the system principal has. */
    private static void runAsSystem(String tenant, Runnable r) {
        Authentication prev = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(tenant == null ? TenantContext.require() : tenant)));
        try { r.run(); } finally { if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev); }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
