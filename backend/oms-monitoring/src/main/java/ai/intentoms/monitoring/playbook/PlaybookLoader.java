package ai.intentoms.monitoring.playbook;

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

/** Seeds playbooks and spend limits from {@code config/tenants/<t>/playbooks.yaml} (FR-MON-04). Idempotent upsert. */
@Component
public class PlaybookLoader {
    private static final Logger log = LoggerFactory.getLogger(PlaybookLoader.class);
    private final PlaybookRepository repo;

    public PlaybookLoader(PlaybookRepository repo) { this.repo = repo; }

    public List<Playbook> loadFile(Path file) {
        try { return load(Json.yamlMap(Files.readString(file))); }
        catch (IOException e) { throw new IllegalStateException("Cannot read playbooks file " + file, e); }
    }

    @SuppressWarnings("unchecked")
    public List<Playbook> load(Map<String, Object> yaml) {
        List<Playbook> out = new ArrayList<>();
        if (yaml.get("playbooks") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> m = (Map<String, Object>) o;
                List<Map<String, Object>> steps = m.get("steps") instanceof List<?> sl ? (List<Map<String, Object>>) (List<?>) sl : List.of();
                out.add(repo.upsert(new Playbook(String.valueOf(m.get("name")), m.get("description") == null ? null : String.valueOf(m.get("description")), steps)));
            }
        }
        if (yaml.get("spendLimits") instanceof Map<?, ?> sl) repo.setSpendLimits((Map<String, Object>) sl);
        log.info("Loaded {} playbooks", out.size());
        return out;
    }
}
