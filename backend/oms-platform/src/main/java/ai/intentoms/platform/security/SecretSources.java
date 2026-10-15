package ai.intentoms.platform.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves secrets that may be supplied inline or through a mounted file (Docker/Kubernetes secrets). */
public final class SecretSources {
    private SecretSources() {}

    /**
     * @param inline inline value (may be a default)
     * @param file   optional path; when non-blank its trimmed content wins. A missing or unreadable file is a configuration
     *               error and fails fast rather than silently falling back to the inline default.
     */
    public static String resolve(String inline, String file) {
        if (file == null || file.isBlank()) return inline;
        try {
            return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read secret file " + file, e);
        }
    }
}
