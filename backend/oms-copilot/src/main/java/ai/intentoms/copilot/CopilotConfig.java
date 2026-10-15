package ai.intentoms.copilot;

import ai.intentoms.copilot.config.CopilotProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wires the copilot module's deploy-time properties ({@code oms.copilot.*}). */
@Configuration
@EnableConfigurationProperties(CopilotProperties.class)
public class CopilotConfig {}
