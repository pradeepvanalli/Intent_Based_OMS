package ai.intentoms.orchestration.config;

import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Module wiring (ARCHITECTURE §3.13): binds {@link OrchestrationProperties} and resolves the tenant-level overrides of the
 * workflow timers. Every other bean of the module is a plain {@code @Component}; the decision {@code EscalationHandler}
 * replacement ({@code HumanTaskEscalationHandler}) is a component too, so the decision module's
 * {@code @ConditionalOnMissingBean} default steps aside.
 */
@Configuration
@EnableConfigurationProperties(OrchestrationProperties.class)
public class OrchestrationConfig {

    /** Resolves a tenant-configured ISO duration, falling back to the deploy-time default when absent or malformed. */
    public static Duration duration(TenantConfigStore config, String key, Duration dflt) {
        String v = config.getString(key, null);
        if (v == null || v.isBlank()) return dflt;
        try { return Duration.parse(v.trim()); } catch (DateTimeParseException e) { return dflt; }
    }
}
