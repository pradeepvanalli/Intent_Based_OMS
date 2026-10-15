package ai.intentoms.agent.registry;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Registration request (FR-AGT-03). {@code publicKey} is optional (base64 X.509 SubjectPublicKeyInfo of an Ed25519 or EC
 * key) and enables asymmetric mandate signatures; otherwise mandates are HMAC-signed with the issued secret.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRegistration(@JsonAlias({"agentId"}) String id, String owner, String protocol, Integer trustTier, AgentLimits limits, String callbackUrl, String publicKey) {}
