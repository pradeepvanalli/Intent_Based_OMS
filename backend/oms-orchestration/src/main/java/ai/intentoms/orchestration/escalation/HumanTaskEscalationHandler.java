package ai.intentoms.orchestration.escalation;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.runtime.EscalationHandler;
import ai.intentoms.decision.runtime.EscalationRequest;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L3 of the decision fabric (FR-DEC-02, GR-07, §8 tier table): every escalated or T3 decision opens a human task carrying
 * the model's recommendation, confidence, band, answers and reason codes, and records the {@code decision_escalation} row
 * with the task id. Queue routing: T3 decisions about returns, agents or addresses → {@code risk_review}; T3 refund /
 * appeasement decisions → {@code refund_approvals}; everything else → {@code decision_escalations}. Completing the task
 * resolves the escalation and, when the person disagrees with the recommendation, records a GR-13 override
 * ({@link DecisionEscalationTaskSubscriber}). Replaces the decision module's recording default.
 */
@Component
public class HumanTaskEscalationHandler implements EscalationHandler {
    public static final String TASK_TYPE = "decision_escalation";
    public static final String QUEUE_RISK = "risk_review";
    public static final String QUEUE_REFUNDS = "refund_approvals";
    public static final String QUEUE_DEFAULT = "decision_escalations";
    private static final Logger log = LoggerFactory.getLogger(HumanTaskEscalationHandler.class);

    private final HumanTaskService tasks;
    private final DecisionEscalationRepository escalations;

    public HumanTaskEscalationHandler(HumanTaskService tasks, DecisionEscalationRepository escalations) {
        this.tasks = tasks;
        this.escalations = escalations;
    }

    @Override
    public String escalate(EscalationRequest r) {
        String id = Ids.prefixed("esc");
        String queue = queueFor(r.tier(), r.decision());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("escalationId", id);
        payload.put("recordId", r.recordId());
        payload.put("decision", r.decision());
        payload.put("version", r.version());
        payload.put("tier", r.tier() == null ? null : r.tier().name());
        payload.put("recommendedValue", r.recommendedValue());
        payload.put("confidence", r.confidence());
        payload.put("band", r.band() == null ? null : r.band().name());
        payload.put("answers", answers(r.answers()));
        payload.put("reasonCodes", r.reasonCodes());
        payload.put("reason", r.reason());
        payload.put("subjectType", r.subjectType());
        payload.put("subjectId", r.subjectId());
        payload.put("options", Map.of("accept", "Act on the recommendation", "override", "Choose another value (details.value) with a reason"));
        String title = "Decision " + r.decision() + " needs a human";
        HumanTask task = tasks.create(new TaskRequest(TASK_TYPE, queue, r.subjectType(), r.subjectId(), title, payload, null, skills(queue), r.tier() == Tier.T3 ? 8 : 4, null));
        escalations.insert(id, r.recordId(), r.decision(), task.id());
        log.info("Decision {} ({}) escalated to {} as task {} (recommendation {}, confidence {})", r.decision(), r.tier(), queue, task.id(), r.recommendedValue(), r.confidence());
        return id;
    }

    /** Queue by tier and decision name (visible for unit tests). */
    public static String queueFor(Tier tier, String decision) {
        if (tier != Tier.T3) return QUEUE_DEFAULT;
        String d = decision == null ? "" : decision.toLowerCase(Locale.ROOT);
        if (d.contains("return") || d.contains("agent") || d.contains("address") || d.contains("fraud")) return QUEUE_RISK;
        if (d.contains("refund") || d.contains("appeasement")) return QUEUE_REFUNDS;
        return QUEUE_DEFAULT;
    }

    private static Set<String> skills(String queue) {
        return switch (queue) { case QUEUE_RISK -> Set.of("risk"); case QUEUE_REFUNDS -> Set.of("finance"); default -> Set.of("ops"); };
    }

    private static Map<String, Object> answers(Map<String, Answer> answers) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (answers == null) return out;
        answers.forEach((k, a) -> out.put(k, Json.toMap(a)));
        return out;
    }
}
