package ai.intentoms.monitoring.exception;

import ai.intentoms.monitoring.playbook.PlaybookStep;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A classified exception (FR-MON-03) with its playbook trail (FR-MON-04). Serialises to the UI contract item 10 row:
 * {@code classificationRecordId, playbook, playbookSteps[{action, status, detail, gatedBy}], slaDueAt}.
 */
public record ExceptionRecord(String id, String orderId, String lineId, String nodeId, String type, String severity, String queue, String status, String source, String code,
                              String message, Map<String, Object> details, String classificationRecordId, Double classificationConfidence, String playbook,
                              List<PlaybookStep> playbookSteps, Instant playbookRunAt, String decisionRecordId, Instant slaDueAt, String assignee, String taskId,
                              String resolution, String resolutionNote, Instant resolvedAt, int occurrences, Instant lastOccurrenceAt, String sourceEventId,
                              Instant createdAt, Instant updatedAt) {
    public ExceptionRecord {
        details = details == null ? Map.of() : Map.copyOf(details);
        playbookSteps = playbookSteps == null ? List.of() : List.copyOf(playbookSteps);
    }

    public boolean isActive() { return ExceptionStatus.isActive(status); }

    /** Ledger aggregate the exception's events land on: the order when known, else the exception itself (ARCHITECTURE §2). */
    public String aggregateType() { return orderId == null ? "exception" : "order"; }
    public String aggregateId() { return orderId == null ? id : orderId; }

    public ExceptionRecord withType(String newType, String newSeverity, String newQueue, String newPlaybook, Instant newSlaDueAt, String recordId, Double confidence) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, newType, newSeverity, newQueue, status, source, code, message, details, recordId, confidence, newPlaybook, playbookSteps,
            playbookRunAt, decisionRecordId, newSlaDueAt, assignee, taskId, resolution, resolutionNote, resolvedAt, occurrences, lastOccurrenceAt, sourceEventId, createdAt, updatedAt);
    }
    public ExceptionRecord withPlaybook(String name) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, type, severity, queue, status, source, code, message, details, classificationRecordId, classificationConfidence, name,
            playbookSteps, playbookRunAt, decisionRecordId, slaDueAt, assignee, taskId, resolution, resolutionNote, resolvedAt, occurrences, lastOccurrenceAt, sourceEventId, createdAt, updatedAt);
    }
    public ExceptionRecord withSteps(List<PlaybookStep> steps, Instant runAt) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, type, severity, queue, status, source, code, message, details, classificationRecordId, classificationConfidence, playbook,
            steps, runAt, decisionRecordId, slaDueAt, assignee, taskId, resolution, resolutionNote, resolvedAt, occurrences, lastOccurrenceAt, sourceEventId, createdAt, updatedAt);
    }
    public ExceptionRecord withTask(String newTaskId) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, type, severity, queue, status, source, code, message, details, classificationRecordId, classificationConfidence, playbook,
            playbookSteps, playbookRunAt, decisionRecordId, slaDueAt, assignee, newTaskId, resolution, resolutionNote, resolvedAt, occurrences, lastOccurrenceAt, sourceEventId, createdAt, updatedAt);
    }
    public ExceptionRecord withDecisionRecord(String recordId, Map<String, Object> newDetails) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, type, severity, queue, status, source, code, message, newDetails, classificationRecordId, classificationConfidence, playbook,
            playbookSteps, playbookRunAt, recordId, slaDueAt, assignee, taskId, resolution, resolutionNote, resolvedAt, occurrences, lastOccurrenceAt, sourceEventId, createdAt, updatedAt);
    }
    public ExceptionRecord withAssignee(String newAssignee, String newStatus) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, type, severity, queue, newStatus, source, code, message, details, classificationRecordId, classificationConfidence, playbook,
            playbookSteps, playbookRunAt, decisionRecordId, slaDueAt, newAssignee, taskId, resolution, resolutionNote, resolvedAt, occurrences, lastOccurrenceAt, sourceEventId, createdAt, updatedAt);
    }
    public ExceptionRecord resolved(String newStatus, String newResolution, String note, Instant at) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, type, severity, queue, newStatus, source, code, message, details, classificationRecordId, classificationConfidence, playbook,
            playbookSteps, playbookRunAt, decisionRecordId, slaDueAt, assignee, taskId, newResolution, note, at, occurrences, lastOccurrenceAt, sourceEventId, createdAt, updatedAt);
    }
    public ExceptionRecord recurred(Instant at) {
        return new ExceptionRecord(id, orderId, lineId, nodeId, type, severity, queue, status, source, code, message, details, classificationRecordId, classificationConfidence, playbook,
            playbookSteps, playbookRunAt, decisionRecordId, slaDueAt, assignee, taskId, resolution, resolutionNote, resolvedAt, occurrences + 1, at, sourceEventId, createdAt, updatedAt);
    }
}
