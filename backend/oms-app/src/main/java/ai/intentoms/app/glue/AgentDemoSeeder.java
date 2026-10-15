package ai.intentoms.app.glue;

import ai.intentoms.agent.registry.AgentLoader;
import ai.intentoms.orchestration.seed.ConfigDirResolver;
import ai.intentoms.platform.bootstrap.TenantBootstrap;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Seeds the demo agent registry from {@code config/tenants/<tenant>/agents/agents.yaml} after the main demo seeder. */
@Component
public class AgentDemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(AgentDemoSeeder.class);
    private final AgentLoader loader;
    private final TenantBootstrap bootstrap;
    private final boolean enabled;
    private final String tenant;
    private final String configDir;

    public AgentDemoSeeder(AgentLoader loader, TenantBootstrap bootstrap, @Value("${oms.demo.seed:false}") boolean enabled,
                           @Value("${oms.demo.tenant:demo}") String tenant, @Value("${oms.config-dir:}") String configDir) {
        this.loader = loader;
        this.bootstrap = bootstrap;
        this.enabled = enabled;
        this.tenant = tenant;
        this.configDir = configDir;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(110)
    public void seed() {
        if (!enabled) return;
        bootstrap.ensureTenant(tenant, tenant);
        Path file = ConfigDirResolver.resolve(configDir, tenant).map(p -> p.resolve("tenants").resolve(tenant).resolve("agents").resolve("agents.yaml")).orElse(null);
        if (file == null || !Files.exists(file)) { log.warn("Agent seed file not found for tenant {}", tenant); return; }
        var prev = SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(tenant)));
            TenantContext.runAs(tenant, () -> log.info("Seeded {} demo agents from {}", loader.loadFile(file).size(), file));
        } finally {
            SecurityContextHolder.getContext().setAuthentication(prev);
        }
    }
}
