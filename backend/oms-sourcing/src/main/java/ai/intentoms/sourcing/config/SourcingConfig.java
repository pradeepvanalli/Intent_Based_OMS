package ai.intentoms.sourcing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SourcingProperties.class)
public class SourcingConfig {}
