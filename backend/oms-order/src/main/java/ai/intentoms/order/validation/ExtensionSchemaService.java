package ai.intentoms.order.validation;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant-defined, typed extension schema (FR-CAP-02: "no untyped blobs"). Table {@code extension_schema}
 * (tenant_id, entity, json_schema); {@code order.extensions} is validated against the {@code order} entity's JSON
 * Schema (draft 2020-12) by the {@code schema} validation step. Schemas are cached per tenant for 5 s.
 */
@Service
public class ExtensionSchemaService {
    public static final String ORDER = "order";
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final JdbcClient jdbc;
    private final Cache<String, Optional<Map<String, Object>>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(1_000).build();

    public ExtensionSchemaService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Map<String, Object>> get(String entity) {
        String tenant = TenantContext.require();
        return cache.get(tenant + "|" + entity, k -> jdbc.sql("select json_schema::text from extension_schema where tenant_id = :t and entity = :e")
            .param("t", tenant).param("e", entity).query(String.class).optional().map(Json::readMap));
    }

    /** Stores (or replaces) the schema after checking it compiles. */
    public Map<String, Object> put(String entity, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) throw new ValidationException("extension schema must be a non-empty JSON Schema object");
        try { compile(schema); } catch (RuntimeException e) { throw new ValidationException("extension schema does not compile: " + e.getMessage()); }
        String tenant = TenantContext.require();
        jdbc.sql("insert into extension_schema(tenant_id, entity, json_schema) values (:t, :e, :s) on conflict (tenant_id, entity) do update set json_schema = excluded.json_schema, updated_at = now()")
            .param("t", tenant).param("e", entity).param("s", Jsonb.of(schema)).update();
        cache.invalidate(tenant + "|" + entity);
        return schema;
    }

    public void delete(String entity) {
        String tenant = TenantContext.require();
        jdbc.sql("delete from extension_schema where tenant_id = :t and entity = :e").param("t", tenant).param("e", entity).update();
        cache.invalidate(tenant + "|" + entity);
    }

    /** Validation messages for {@code data} against {@code schema} (empty = valid). Pure; usable without Spring. */
    public static List<String> validate(Map<String, Object> schema, Map<String, Object> data) {
        if (schema == null || schema.isEmpty()) return List.of();
        JsonSchema compiled = compile(schema);
        JsonNode node = Json.toTree(data == null ? Map.of() : data);
        Set<ValidationMessage> messages = compiled.validate(node);
        return messages.stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    private static JsonSchema compile(Map<String, Object> schema) { return FACTORY.getSchema(Json.toTree(schema)); }
}
