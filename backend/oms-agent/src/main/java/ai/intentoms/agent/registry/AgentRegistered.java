package ai.intentoms.agent.registry;

/** Result of a registration: the identity plus the HMAC secret and platform API key, both shown exactly once. */
public record AgentRegistered(AgentIdentity agent, String secret, String apiKey, String apiKeyId, String note) {}
