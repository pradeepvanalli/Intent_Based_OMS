package ai.intentoms.finance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the finance module's deploy-time properties. All beans are discovered by component scanning. */
@Configuration
@EnableConfigurationProperties(FinanceProperties.class)
public class FinanceConfig {}
