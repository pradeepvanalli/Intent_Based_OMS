package ai.intentoms.statemachine.service;

import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads Git-managed model files (FR-SM-09) such as {@code config/tenants/demo/state-models/*.yaml} into the registry of
 * the tenant bound to the current thread. A file whose exact (id, version) is already published is skipped, so the loader
 * is idempotent and safe to run on every start-up.
 */
@Component
public class StateModelLoader {
    private static final Logger log = LoggerFactory.getLogger(StateModelLoader.class);
    private final StateModelRegistry registry;

    public StateModelLoader(StateModelRegistry registry) { this.registry = registry; }

    /** @return the models newly published by this call (already-published versions are not included) */
    public List<StateModel> loadDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            log.warn("State model directory {} does not exist; nothing loaded", dir);
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> { String n = p.getFileName().toString(); return n.endsWith(".yaml") || n.endsWith(".yml"); }).sorted().toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }
        List<StateModel> published = new ArrayList<>();
        for (Path f : files) {
            String yaml;
            try { yaml = Files.readString(f, StandardCharsets.UTF_8); } catch (IOException e) { throw new UncheckedIOException("Cannot read " + f, e); }
            StateModel parsed = StateModelParser.parse(yaml);
            if (registry.exists(parsed.id(), parsed.version())) {
                log.debug("State model {} v{} already published; skipping {}", parsed.id(), parsed.version(), f.getFileName());
                continue;
            }
            published.add(registry.publish(yaml, "Loaded from " + f.getFileName()));
            log.info("Published state model {} v{} from {}", parsed.id(), parsed.version(), f.getFileName());
        }
        return published;
    }

    /** Publish a single file (skipped when that version already exists). */
    public StateModel loadFile(Path file) {
        String yaml;
        try { yaml = Files.readString(file, StandardCharsets.UTF_8); } catch (IOException e) { throw new UncheckedIOException("Cannot read " + file, e); }
        StateModel parsed = StateModelParser.parse(yaml);
        if (registry.exists(parsed.id(), parsed.version())) return registry.get(parsed.id(), parsed.version());
        return registry.publish(yaml, "Loaded from " + file.getFileName());
    }
}
