package ai.intentoms.order.service;

import ai.intentoms.order.model.OrderType;
import ai.intentoms.platform.json.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Seeds order types from {@code order-types.yaml} ({@code orderTypes: [...]}) with idempotent upserts (FR-CAP-03). */
@Component
public class OrderTypeLoader {
    private static final Logger log = LoggerFactory.getLogger(OrderTypeLoader.class);
    private final OrderTypeRegistry registry;

    public OrderTypeLoader(OrderTypeRegistry registry) { this.registry = registry; }

    @Transactional
    public List<OrderType> loadFile(Path file) {
        String yaml;
        try { yaml = Files.readString(file); } catch (IOException e) { throw new UncheckedIOException("Cannot read " + file, e); }
        List<OrderType> out = loadYaml(yaml);
        log.info("Loaded {} order types from {}", out.size(), file.getFileName());
        return out;
    }

    @Transactional
    public List<OrderType> loadYaml(String yaml) {
        Map<String, Object> root = Json.yamlMap(yaml);
        Object types = root.get("orderTypes");
        if (!(types instanceof List<?>)) throw new IllegalArgumentException("order-types.yaml must contain an 'orderTypes' list");
        List<OrderType> parsed = Json.convert(types, new TypeReference<List<OrderType>>() {});
        List<OrderType> out = new ArrayList<>();
        for (OrderType t : parsed) out.add(registry.upsert(t));
        return out;
    }
}
