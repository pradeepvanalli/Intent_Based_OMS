package ai.intentoms.agent.registry;

import java.time.Instant;
import java.util.Map;

/** One tool call by an agent (FR-AGT-04 audit trail, table {@code agent_activity}). */
public record AgentActivity(long id, String agentId, Instant at, String tool, String outcome, String orderId, Long latencyMs, Map<String, Object> details) {}
