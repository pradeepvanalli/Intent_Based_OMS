package ai.intentoms.workflow.model;

import java.time.Instant;
import java.util.Map;

/** One line of the per-instance timeline (FR-WF-06). Kinds: step, activity, signal, timer, task, log, error, compensation, workflow. */
public record TimelineEntry(Instant at, String kind, String name, String status, Map<String, Object> details) {}
