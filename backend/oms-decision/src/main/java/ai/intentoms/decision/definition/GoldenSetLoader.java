package ai.intentoms.decision.definition;

import ai.intentoms.decision.model.GoldenCase;
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
import java.util.stream.Stream;

/**
 * Loads golden sets from {@code *.json} or {@code *.yaml} files (GR-11). A file is either a list of cases or a map
 * {@code {goldenSet, cases: [...]}}; each case is {@code {caseId, input, expected: {value, band?}, notes?}}. The golden
 * set name defaults to the file name without extension.
 */
@Component
public class GoldenSetLoader {
    private static final Logger log = LoggerFactory.getLogger(GoldenSetLoader.class);
    private final GoldenCaseRepository repo;

    public GoldenSetLoader(GoldenCaseRepository repo) { this.repo = repo; }

    /** Loads every golden set file in the directory for the current tenant; returns the number of cases upserted. */
    public int loadDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        int total = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> { String n = p.getFileName().toString(); return n.endsWith(".json") || n.endsWith(".yaml") || n.endsWith(".yml"); }).sorted().toList()) {
                List<GoldenCase> cases = parse(f);
                repo.upsertAll(cases);
                total += cases.size();
                log.info("Loaded golden set {} with {} cases", f.getFileName(), cases.size());
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot read golden sets from " + dir, e); }
        return total;
    }

    @SuppressWarnings("unchecked")
    public List<GoldenCase> parse(Path file) {
        try {
            String text = Files.readString(file);
            String fileName = file.getFileName().toString();
            String defaultSet = fileName.substring(0, fileName.lastIndexOf('.'));
            Object root = fileName.endsWith(".json") ? Json.read(text, Object.class) : Json.YAML.readValue(text, Object.class);
            String set = defaultSet;
            List<Object> items;
            if (root instanceof Map<?, ?> m) {
                if (m.get("goldenSet") != null) set = String.valueOf(m.get("goldenSet"));
                Object cs = m.get("cases");
                items = cs instanceof List<?> cl ? (List<Object>) cl : List.of();
            } else items = (List<Object>) root;
            List<GoldenCase> out = new ArrayList<>();
            int i = 0;
            for (Object o : items) {
                Map<String, Object> c = (Map<String, Object>) o;
                String caseId = c.get("caseId") != null ? String.valueOf(c.get("caseId")) : set + "_" + (++i);
                Object exp = c.get("expected");
                Map<String, Object> expected = exp instanceof Map<?, ?> em ? (Map<String, Object>) em : new java.util.LinkedHashMap<>(Map.of("value", exp));
                out.add(new GoldenCase(set, caseId, (Map<String, Object>) c.getOrDefault("input", Map.of()), expected, c.get("notes") == null ? null : String.valueOf(c.get("notes"))));
            }
            return out;
        } catch (IOException e) { throw new IllegalStateException("Cannot read golden set " + file, e); }
    }
}
