package ai.intentoms.workflow.model;

import java.time.Instant;
import java.util.Map;

/** An external event delivered to a workflow instance (FR-WF-05). Task completions arrive as {@code task:<taskId>}. */
public record Signal(String name, Map<String, Object> payload, String idempotencyKey, Instant receivedAt) {}
