package ai.intentoms.agent.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Partial update of an agent (FR-AGT-04): null fields are left unchanged; {@code limits} merges field by field. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentUpdate(String owner, Integer trustTier, AgentLimits limits, String callbackUrl, String publicKey, Boolean active) {}
