package ai.intentoms.order.capture;

import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.platform.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds {@code catalog_item} from {@code config/tenants/<t>/catalog.yaml} ({@code products: [{sku, name, price, cogs, weightKg, ...}]}).
 * Idempotent upsert, so the demo seeder may run it on every start.
 */
@Component
public class CatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(CatalogLoader.class);
    private final CatalogService catalog;

    public CatalogLoader(CatalogService catalog) { this.catalog = catalog; }

    @Transactional
    public List<CatalogItem> loadFile(Path file) {
        String yaml;
        try { yaml = Files.readString(file); } catch (IOException e) { throw new UncheckedIOException("Cannot read " + file, e); }
        List<CatalogItem> out = loadYaml(yaml);
        log.info("Loaded {} catalogue items from {}", out.size(), file.getFileName());
        return out;
    }

    @Transactional
    public List<CatalogItem> loadYaml(String yaml) {
        Map<String, Object> root = Json.yamlMap(yaml);
        Object products = root.get("products");
        if (!(products instanceof List<?> list)) throw new IllegalArgumentException("catalog.yaml must contain a 'products' list");
        List<CatalogItem> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> attrs = new LinkedHashMap<>();
            m.forEach((k, v) -> { if (v != null && !"sku".equals(k) && !"name".equals(k)) attrs.put(String.valueOf(k), v); });
            CatalogItem item = new CatalogItem(String.valueOf(m.get("sku")), m.get("name") == null ? null : String.valueOf(m.get("name")), attrs);
            out.add(catalog.upsert(item));
        }
        return out;
    }
}
