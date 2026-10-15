package ai.intentoms.monitoring.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link MonitoringProperties}; scheduling itself is enabled by the platform configuration. */
@Configuration
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfig {}
