package ai.intentoms.app;

import ai.intentoms.platform.security.SecretSources;
import ai.intentoms.platform.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast production guard (NFR-07/NFR-08). Active when the {@code prod} profile is on or
 * {@code oms.security.enforce-production=true}; runs as soon as the Environment is prepared, before any bean or
 * database connection exists, and aborts startup with one exception that lists every violation:
 * <ul>
 *   <li>default or short (&lt; 32 chars) JWT secret (inline or via {@code oms.security.jwt-secret-file})</li>
 *   <li>{@code oms.security.dev-users} configured</li>
 *   <li>{@code oms.demo.seed=true} or {@code oms.agent.demo-expose-secrets=true}</li>
 *   <li>CORS allowing any origin ({@code oms.security.cors.allowed-origins} contains {@code *} or is empty)</li>
 *   <li>actuator exposing anything beyond health and prometheus</li>
 *   <li>no {@code oms.security.master-key} for tenant PII key envelope encryption</li>
 * </ul>
 * Registered through {@code META-INF/spring.factories} so it also runs for {@code @SpringBootTest} contexts.
 */
public class ProductionReadinessCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessCheck.class);
    static final Set<String> ALLOWED_ACTUATOR = Set.of("health", "prometheus");
    static final int MIN_SECRET_LENGTH = 32;

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; } // after config-data processing so application-prod.yml is loaded

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        if (!enforced(env)) return;
        List<String> violations = violations(env);
        if (!violations.isEmpty()) throw new NotProductionReadyException(violations);
        log.info("Production readiness check passed");
    }

    public static boolean enforced(Environment env) {
        return env.acceptsProfiles(Profiles.of("prod")) || env.getProperty("oms.security.enforce-production", Boolean.class, false);
    }

    /** Every violation found in the given environment (empty when production-ready). Pure: safe to call from tests. */
    public static List<String> violations(Environment env) {
        List<String> v = new ArrayList<>();
        Binder binder = Binder.get(env);

        String secretFile = env.getProperty("oms.security.jwt-secret-file", "");
        String secret = env.getProperty("oms.security.jwt-secret", SecurityProperties.DEFAULT_JWT_SECRET);
        try {
            secret = SecretSources.resolve(secret, secretFile);
        } catch (RuntimeException e) {
            v.add("oms.security.jwt-secret-file '" + secretFile + "' cannot be read: " + e.getMessage());
        }
        if (SecurityProperties.DEFAULT_JWT_SECRET.equals(secret) || secret.toLowerCase(Locale.ROOT).contains("change-me")) {
            v.add("oms.security.jwt-secret is the default placeholder; set OMS_JWT_SECRET (or oms.security.jwt-secret-file) to a random value of at least " + MIN_SECRET_LENGTH + " characters");
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            v.add("oms.security.jwt-secret is too short (" + secret.length() + " chars, minimum " + MIN_SECRET_LENGTH + ")");
        }

        List<String> devUsers = binder.bind("oms.security.dev-users", Bindable.listOf(String.class)).orElse(List.of());
        if (devUsers.stream().anyMatch(s -> s != null && !s.isBlank())) {
            v.add("oms.security.dev-users must be empty in production (" + devUsers.size() + " bootstrap user(s) configured; set OMS_DEV_USERS to an empty string)");
        }

        if (env.getProperty("oms.demo.seed", Boolean.class, false)) v.add("oms.demo.seed must be false in production (OMS_DEMO_SEED=false)");
        if (env.getProperty("oms.agent.demo-expose-secrets", Boolean.class, false)) v.add("oms.agent.demo-expose-secrets must be false in production");

        List<String> origins = binder.bind("oms.security.cors.allowed-origins", Bindable.listOf(String.class)).orElse(List.of("*"));
        if (origins.isEmpty() || origins.stream().anyMatch(o -> o == null || o.isBlank() || o.contains("*"))) {
            v.add("oms.security.cors.allowed-origins must list explicit browser origins (currently " + origins + "); set OMS_CORS_ALLOWED_ORIGINS");
        }

        List<String> exposure = binder.bind("management.endpoints.web.exposure.include", Bindable.listOf(String.class)).orElse(List.of());
        List<String> extra = exposure.stream().map(String::trim).filter(e -> !e.isEmpty() && !ALLOWED_ACTUATOR.contains(e)).toList();
        if (!extra.isEmpty()) v.add("management.endpoints.web.exposure.include exposes " + extra + "; only " + ALLOWED_ACTUATOR + " are allowed in production");

        if (env.getProperty("oms.security.master-key", "").isBlank()) {
            v.add("oms.security.master-key is not set; tenant PII keys would be stored unwrapped (set OMS_MASTER_KEY)");
        }
        return v;
    }

    /** Thrown with every violation in the message so operators fix them in one pass. */
    public static final class NotProductionReadyException extends IllegalStateException {
        private final List<String> violations;

        NotProductionReadyException(List<String> violations) {
            super("Refusing to start in production mode: " + violations.size() + " violation(s)\n  - " + String.join("\n  - ", violations)
                + "\nSee backend/oms-platform/HARDENING.md (production checklist).");
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() { return violations; }
    }
}
