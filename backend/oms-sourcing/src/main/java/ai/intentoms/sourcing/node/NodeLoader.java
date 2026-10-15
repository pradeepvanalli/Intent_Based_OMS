package ai.intentoms.sourcing.node;

import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.sourcing.model.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds nodes from {@code nodes.yaml} (idempotent upsert, FR-SRC-01). Format: top-level {@code nodes:} list of maps with
 * id, name, type, lat, lon, country, state, zip, capabilities[], dailyCapacity, cutoff "HH:mm", timezone, health{},
 * costProfile{}, landedCost{}, active (default true).
 */
@Service
public class NodeLoader {
    private static final Logger log = LoggerFactory.getLogger(NodeLoader.class);
    private final DefaultNodeRegistry registry;
    private final NodeRepository repo;
    private final AuditLog audit;

    public NodeLoader(DefaultNodeRegistry registry, NodeRepository repo, AuditLog audit) {
        this.registry = registry;
        this.repo = repo;
        this.audit = audit;
    }

    public List<Node> loadFile(Path file) {
        try { return loadYaml(Files.readString(file), file.toString()); }
        catch (IOException e) { throw new ValidationException("Cannot read nodes file " + file + ": " + e.getMessage()); }
    }

    /** Parses and upserts every node without emitting per-node events (seed path); one audit row records the load. */
    @Transactional
    public List<Node> loadYaml(String yaml, String source) {
        List<Node> nodes = parse(yaml);
        for (Node n : nodes) repo.upsert(n);
        registry.invalidate();
        audit.record("nodes.loaded", "node", source, "Loaded " + nodes.size() + " nodes", Map.of("nodeIds", nodes.stream().map(Node::id).toList()));
        log.info("Loaded {} nodes from {}", nodes.size(), source);
        return nodes;
    }

    public static List<Node> parse(String yaml) {
        Map<String, Object> doc = Json.yamlMap(yaml);
        if (!(doc.get("nodes") instanceof List<?> list)) throw new ValidationException("nodes.yaml must contain a top-level 'nodes' list");
        List<Node> out = new ArrayList<>(list.size());
        Set<String> ids = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new ValidationException("node entries must be maps");
            Node n = fromMap(m);
            if (!ids.add(n.id())) throw new ValidationException("duplicate node id " + n.id());
            out.add(n);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Node fromMap(Map<?, ?> m) {
        String id = str(m.get("id"));
        if (id == null || id.isBlank()) throw new ValidationException("node.id is required");
        Set<String> caps = m.get("capabilities") instanceof List<?> l ? new LinkedHashSet<>(l.stream().map(String::valueOf).toList()) : Set.of();
        Object cutoff = m.get("cutoff");
        LocalTime ct = cutoff == null ? LocalTime.of(17, 0) : LocalTime.parse(cutoff.toString().length() == 5 ? cutoff.toString() : cutoff.toString());
        Object active = m.get("active");
        return new Node(id, str(m.get("name")), str(or_(m, "type", "dc")), num(m.get("lat")), num(m.get("lon")), str(or_(m, "country", "US")), str(m.get("state")),
            str(m.get("zip")), caps, (int) num(or_(m, "dailyCapacity", 0)), ct, str(or_(m, "timezone", "UTC")),
            m.get("health") instanceof Map<?, ?> h ? (Map<String, Object>) h : Map.of(),
            m.get("costProfile") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of(),
            m.get("landedCost") instanceof Map<?, ?> lc ? (Map<String, Object>) lc : Map.of(),
            active == null || Boolean.parseBoolean(active.toString()), 0.0);
    }

    private static Object or_(Map<?, ?> m, String k, Object dflt) { Object v = m.get(k); return v == null ? dflt : v; }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : o == null ? 0.0 : Double.parseDouble(o.toString()); }
}
