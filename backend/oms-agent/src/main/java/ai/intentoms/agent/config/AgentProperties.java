package ai.intentoms.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deploy-time settings of the agent module ({@code oms.agent.*}).
 *
 * @param demoExposeSecrets  when true the {@code AgentLoader} also writes generated secrets to tenant_config key
 *                           {@code agents.demo_secrets} so the demo can sign MCP/UCP calls; never enable in production
 * @param signatureWindow    maximum clock skew accepted on {@code X-Agent-Timestamp} (GR-14 replay defence, default ±300 s)
 * @param maxBodyBytes       largest request body the signing filter will buffer for hashing
 * @param secretKey          key material for encrypting agent HMAC secrets at rest; falls back to the JWT secret when blank
 * @param webhookTimeout     connect/read timeout for UCP lifecycle webhooks
 * @param serverVersion      version advertised in MCP {@code initialize}
 */
@ConfigurationProperties(prefix = "oms.agent")
public record AgentProperties(
    @DefaultValue("false") boolean demoExposeSecrets,
    @DefaultValue("PT300S") Duration signatureWindow,
    @DefaultValue("1048576") int maxBodyBytes,
    @DefaultValue("") String secretKey,
    @DefaultValue("PT5S") Duration webhookTimeout,
    @DefaultValue("0.1.0") String serverVersion
) {}
