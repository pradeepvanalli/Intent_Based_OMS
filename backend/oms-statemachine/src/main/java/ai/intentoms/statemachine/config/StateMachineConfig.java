package ai.intentoms.statemachine.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link StateMachineProperties}; scheduling is enabled by the platform configuration. */
@Configuration
@EnableConfigurationProperties(StateMachineProperties.class)
public class StateMachineConfig {}
