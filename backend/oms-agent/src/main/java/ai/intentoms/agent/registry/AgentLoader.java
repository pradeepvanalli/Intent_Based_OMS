package ai.intentoms.agent.registry;

import ai.intentoms.agent.config.AgentConfigKeys;
import ai.intentoms.agent.config.AgentProperties;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the registry from {@code config/tenants/<t>/agents/agents.yaml} ({@code agents: [{id, owner, protocol, trustTier,
 * allowedActions, maxOrderValueUsd, maxRefundUsd, ratePerMinute, confirmActions, callbackUrl?}]}) (FR-AGT-03/04).
 * Idempotent: agents that already exist are left untouched. Generated secrets and API keys are logged once at INFO and,
 * only when {@code oms.agent.demo-expose-secrets=true}, written to tenant_config {@code agents.demo_secrets}.
 */
@Component
public class AgentLoader {
    private static final Logger log = LoggerFactory.getLogger(AgentLoader.class);
    private final AgentRegistry registry;
    private final TenantConfigStore config;
    private final AgentProperties props;

    public AgentLoader(AgentRegistry registry, TenantConfigStore config, AgentProperties props) {
        this.registry = registry;
        this.config = config;
        this.props = props;
    }

    public List<AgentRegistered> loadFile(Path file) {
        String yaml;
        try { yaml = Files.readString(file); } catch (IOException e) { throw new UncheckedIOException("Cannot read " + file, e); }
        List<AgentRegistered> out = loadYaml(yaml);
        log.info("Seeded {} new agents from {}", out.size(), file.getFileName());
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<AgentRegistered> loadYaml(String yaml) {
        Map<String, Object> root = Json.yamlMap(yaml);
        Object agents = root.get("agents");
        if (!(agents instanceof List<?> list)) throw new IllegalArgumentException("agents.yaml must contain an 'agents' list");
        List<AgentRegistered> created = new ArrayList<>();
        Map<String, Object> exposed = new LinkedHashMap<>(props.demoExposeSecrets() ? config.getMap(AgentConfigKeys.DEMO_SECRETS) : Map.of());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = (Map<String, Object>) raw;
            String id = str(m.get("id"));
            if (id == null) continue;
            if (registry.find(id).isPresent()) { log.debug("Agent {} already registered, skipping", id); continue; }
            AgentLimits limits = new AgentLimits(strings(m.get("allowedActions")), decimal(m.get("maxOrderValueUsd")), decimal(m.get("maxRefundUsd")),
                m.get("ratePerMinute") instanceof Number n ? n.intValue() : null, strings(m.get("confirmActions")));
            AgentRegistration reg = new AgentRegistration(id, str(m.get("owner")), str(m.get("protocol")), m.get("trustTier") instanceof Number n ? n.intValue() : null,
                limits, str(m.get("callbackUrl")), str(m.get("publicKey")));
            AgentRegistered r = registry.register(reg);
            created.add(r);
            log.info("Registered demo agent {} (tier {}, protocol {}) secret={} apiKey={} -- shown once", id, r.agent().trustTier(), r.agent().protocol(), r.secret(), r.apiKey());
            if (props.demoExposeSecrets()) exposed.put(id, Map.of("secret", r.secret(), "apiKey", r.apiKey(), "apiKeyId", r.apiKeyId()));
        }
        if (props.demoExposeSecrets() && !created.isEmpty()) config.set(AgentConfigKeys.DEMO_SECRETS, exposed);
        return created;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static BigDecimal decimal(Object o) { return o instanceof Number n ? new BigDecimal(n.toString()) : o instanceof String s && !s.isBlank() ? new BigDecimal(s) : null; }
    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) { return o instanceof List<?> l ? ((List<Object>) l).stream().map(String::valueOf).toList() : List.of(); }
}
