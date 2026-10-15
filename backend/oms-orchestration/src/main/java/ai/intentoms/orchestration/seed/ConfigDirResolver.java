package ai.intentoms.orchestration.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the {@code config} directory holding {@code tenants/<tenant>/...}: the configured path (relative to the working
 * directory) first, then {@code ../../config}, {@code ../config}, {@code config} and {@code /config} (containers).
 */
public final class ConfigDirResolver {
    private ConfigDirResolver() {}

    public static Optional<Path> resolve(String configured, String tenant) {
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        candidates.add(Path.of("..", "..", "config"));
        candidates.add(Path.of("..", "config"));
        candidates.add(Path.of("config"));
        candidates.add(Path.of("/config"));
        for (Path c : candidates) {
            Path abs = c.toAbsolutePath().normalize();
            if (Files.isDirectory(abs.resolve("tenants").resolve(tenant))) return Optional.of(abs);
        }
        return Optional.empty();
    }
}
