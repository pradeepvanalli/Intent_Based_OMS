package ai.intentoms.order.api;

import ai.intentoms.order.validation.ExtensionSchemaService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Tenant extension JSON Schema for order extensions (FR-CAP-02). {@code entity} defaults to {@code order}. */
@RestController
@RequestMapping("/api/v1/extension-schema")
public class ExtensionSchemaController {
    private final ExtensionSchemaService schemas;

    public ExtensionSchemaController(ExtensionSchemaService schemas) { this.schemas = schemas; }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public Map<String, Object> get(@RequestParam(defaultValue = ExtensionSchemaService.ORDER) String entity) { return schemas.get(entity).orElse(Map.of()); }

    @PutMapping
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> put(@RequestParam(defaultValue = ExtensionSchemaService.ORDER) String entity, @RequestBody Map<String, Object> schema) { return schemas.put(entity, schema); }

    @DeleteMapping
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public void delete(@RequestParam(defaultValue = ExtensionSchemaService.ORDER) String entity) { schemas.delete(entity); }
}
