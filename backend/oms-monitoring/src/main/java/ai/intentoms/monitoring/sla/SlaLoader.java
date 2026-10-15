package ai.intentoms.monitoring.sla;

import ai.intentoms.platform.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Seeds {@code sla_definition} from {@code config/tenants/<t>/sla.yaml} (FR-MON-02): the {@code slas} list and the
 * {@code expectedNextEventWithin} map (stored as order type {@code *}, entity {@code order}). Idempotent upsert.
 */
@Component
public class SlaLoader {
    private static final Logger log = LoggerFactory.getLogger(SlaLoader.class);
    private final SlaDefinitionRepository repo;

    public SlaLoader(SlaDefinitionRepository repo) { this.repo = repo; }

    public List<SlaDefinition> loadFile(Path file) {
        try { return load(Json.yamlMap(Files.readString(file))); }
        catch (IOException e) { throw new IllegalStateException("Cannot read SLA file " + file, e); }
    }

    @SuppressWarnings("unchecked")
    public List<SlaDefinition> load(Map<String, Object> yaml) {
        List<SlaDefinition> out = new ArrayList<>();
        Object slas = yaml.get("slas");
        if (slas instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> m = (Map<String, Object>) o;
                out.add(repo.upsert(new SlaDefinition(null, str(m.get("orderType")), str(m.get("entity")), str(m.get("state")), str(m.get("maxAge")), str(m.get("onBreach")),
                    str(m.get("expectedNextEventWithin")), null)));
            }
        }
        Object expected = yaml.get("expectedNextEventWithin");
        if (expected instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) out.add(repo.upsert(new SlaDefinition(null, "*", "order", String.valueOf(e.getKey()), null, null, String.valueOf(e.getValue()), null)));
        }
        log.info("Loaded {} SLA definitions", out.size());
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
