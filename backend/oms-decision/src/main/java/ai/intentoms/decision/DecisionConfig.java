package ai.intentoms.decision;

import ai.intentoms.decision.provider.JevProperties;
import ai.intentoms.decision.provider.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wires the decision module's deploy-time properties ({@code oms.decision.jev.*}, {@code oms.decision.llm.*}). */
@Configuration
@EnableConfigurationProperties({JevProperties.class, LlmProperties.class})
public class DecisionConfig {}
