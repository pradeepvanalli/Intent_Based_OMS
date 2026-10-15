package ai.intentoms.monitoring.exception;

import ai.intentoms.workflow.task.TaskRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the human task that represents an exception in a queue (FR-MON-03, FR-WF-04): type {@code exception}, subject the order when known. */
@Component
public class ExceptionTaskFactory {
    public static final String TASK_TYPE = "exception";

    public TaskRequest request(ExceptionRecord e, String queue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exceptionId", e.id());
        payload.put("type", e.type());
        payload.put("severity", e.severity());
        payload.put("source", e.source());
        payload.put("code", e.code());
        payload.put("message", e.message());
        if (e.orderId() != null) payload.put("orderId", e.orderId());
        if (e.lineId() != null) payload.put("lineId", e.lineId());
        if (e.nodeId() != null) payload.put("nodeId", e.nodeId());
        if (e.playbook() != null) payload.put("playbook", e.playbook());
        if (e.classificationRecordId() != null) payload.put("classificationRecordId", e.classificationRecordId());
        String title = e.type().replace('_', ' ') + (e.orderId() == null ? "" : " on " + e.orderId()) + ": " + abbreviate(e.message());
        Duration sla = e.slaDueAt() == null || e.createdAt() == null ? null : Duration.between(e.createdAt(), e.slaDueAt());
        int priority = switch (e.severity()) { case "critical" -> 100; case "high" -> 75; case "medium" -> 50; default -> 25; };
        return new TaskRequest(TASK_TYPE, queue, e.aggregateType(), e.aggregateId(), title, payload, sla != null && !sla.isNegative() ? sla : null, null, priority, null);
    }

    private static String abbreviate(String s) { return s == null ? "" : s.length() > 120 ? s.substring(0, 117) + "..." : s; }
}
