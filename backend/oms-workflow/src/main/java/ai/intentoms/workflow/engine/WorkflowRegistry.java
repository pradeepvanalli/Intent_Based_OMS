package ai.intentoms.workflow.engine;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.workflow.model.WorkflowDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Index of {@link WorkflowDefinition} beans by (type, version) (FR-WF-08). New instances resolve {@link #latest};
 * running instances resolve their pinned version with {@link #get}. Beans are collected lazily on first use so
 * definitions may depend on services that depend on the engine without a construction cycle. Additional
 * definitions can be {@link #register registered} at runtime (used when a new version is loaded without a restart).
 */
@Component
public class WorkflowRegistry {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);
    private final ObjectProvider<WorkflowDefinition> beans;
    private final Map<String, TreeMap<Integer, WorkflowDefinition>> byType = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public WorkflowRegistry(ObjectProvider<WorkflowDefinition> beans) { this.beans = beans; }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            beans.orderedStream().forEach(this::register);
            loaded = true;
        }
    }

    /** Validate and index a definition; a duplicate (type, version) is rejected. */
    public synchronized void register(WorkflowDefinition def) {
        validate(def);
        TreeMap<Integer, WorkflowDefinition> versions = byType.computeIfAbsent(def.type(), t -> new TreeMap<>());
        WorkflowDefinition existing = versions.get(def.version());
        if (existing != null && existing != def) {
            throw new IllegalStateException("Duplicate workflow definition " + def.type() + " v" + def.version() + ": " + existing.getClass().getName() + " and " + def.getClass().getName());
        }
        versions.put(def.version(), def);
        log.info("Registered workflow definition {} v{} ({} steps)", def.type(), def.version(), def.steps().size());
    }

    private static void validate(WorkflowDefinition def) {
        if (def.type() == null || def.type().isBlank()) throw new ValidationException("Workflow definition " + def.getClass().getName() + " has no type");
        if (def.version() < 1) throw new ValidationException("Workflow definition " + def.type() + " has version " + def.version() + "; versions start at 1");
        if (def.steps() == null || def.steps().isEmpty()) throw new ValidationException("Workflow definition " + def.type() + " v" + def.version() + " has no steps");
        if (def.initialStep() == null || !def.steps().containsKey(def.initialStep())) {
            throw new ValidationException("Workflow definition " + def.type() + " v" + def.version() + " initial step '" + def.initialStep() + "' is not a declared step");
        }
    }

    /** Highest registered version of {@code type}. */
    public WorkflowDefinition latest(String type) {
        ensureLoaded();
        TreeMap<Integer, WorkflowDefinition> versions = byType.get(type);
        if (versions == null || versions.isEmpty()) throw new NotFoundException("No workflow definition registered for type '" + type + "'");
        return versions.lastEntry().getValue();
    }

    public Optional<WorkflowDefinition> find(String type, int version) {
        ensureLoaded();
        TreeMap<Integer, WorkflowDefinition> versions = byType.get(type);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    public WorkflowDefinition get(String type, int version) {
        return find(type, version).orElseThrow(() -> new NotFoundException("No workflow definition registered for type '" + type + "' v" + version));
    }

    public List<String> types() {
        ensureLoaded();
        List<String> t = new ArrayList<>(byType.keySet());
        Collections.sort(t);
        return t;
    }

    public List<WorkflowDefinition> versions(String type) {
        ensureLoaded();
        TreeMap<Integer, WorkflowDefinition> versions = byType.get(type);
        return versions == null ? List.of() : versions.values().stream().sorted(Comparator.comparingInt(WorkflowDefinition::version)).toList();
    }
}
