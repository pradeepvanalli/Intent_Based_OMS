package ai.intentoms.orchestration.seed;

import ai.intentoms.platform.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The presentation keys a tenant folder may carry at the top of {@code tenant-config.yaml}: {@code name}, {@code vertical}
 * and {@code description}. They describe the tenant to people (login page, seed log) and are <em>not</em> written to
 * {@code tenant_config}; the seeder skips {@link #KEYS} when loading runtime settings. Missing keys fall back to the id.
 */
public record TenantMetadata(String tenantId, String name, String vertical, String description) {
    public static final Set<String> KEYS = Set.of("name", "vertical", "description");

    public static TenantMetadata of(String tenantId, Map<String, Object> tenantConfig) {
        String name = str(tenantConfig.get("name"));
        return new TenantMetadata(tenantId, name == null ? tenantId : name, str(tenantConfig.get("vertical")), str(tenantConfig.get("description")));
    }

    /** Reads {@code <dir>/tenant-config.yaml}; a missing or unreadable file yields id-only metadata. */
    public static TenantMetadata read(Path tenantDir, String tenantId) {
        Path f = tenantDir.resolve("tenant-config.yaml");
        if (!Files.isRegularFile(f)) return new TenantMetadata(tenantId, tenantId, null, null);
        try { return of(tenantId, Json.yamlMap(Files.readString(f))); }
        catch (IOException | IllegalArgumentException e) { return new TenantMetadata(tenantId, tenantId, null, null); }
    }

    private static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString().trim(); }
}
