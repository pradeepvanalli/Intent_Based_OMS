package ai.intentoms.decision.definition;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Seeds decision definitions from YAML files (FR-PRM-07 template library; used by the demo seeder). Each file is
 * published as live when that name/version does not exist yet, bypassing approvals with reason "seed"; a previously
 * live version of the same name is retired. Files that already exist are left untouched.
 */
@Component
public class DecisionDefinitionLoader {
    private static final Logger log = LoggerFactory.getLogger(DecisionDefinitionLoader.class);
    private final DecisionDefinitionRepository repo;
    private final DecisionDefinitionParser parser;
    private final EventStore events;
    private final DefinitionCache cache;

    public DecisionDefinitionLoader(DecisionDefinitionRepository repo, DecisionDefinitionParser parser, EventStore events, DefinitionCache cache) {
        this.repo = repo;
        this.parser = parser;
        this.events = events;
        this.cache = cache;
    }

    /** Loads all {@code *.yaml|*.yml} files; returns the definitions that were newly published. */
    public List<DecisionDefinition> loadDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<DecisionDefinition> published = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".yaml") || p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                String yaml = Files.readString(f);
                DecisionDefinition def = parser.parse(yaml);
                if (repo.find(def.name(), def.version()).isPresent()) { log.debug("Decision {} already present, skipping", def.key()); continue; }
                published.add(seed(def));
                log.info("Seeded decision {} as live from {}", def.key(), f.getFileName());
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot read decisions from " + dir, e); }
        return published;
    }

    /** Publish one parsed definition as live without approvals (seed path). */
    @Transactional
    public DecisionDefinition seed(DecisionDefinition parsed) {
        DecisionDefinition def = parsed.withStatus("live", 0, "seed", parsed.ticket(), null);
        for (var live : repo.byStatus(def.name(), "live", "canary", "shadow")) repo.setStatus(def.name(), live.definition().version(), "retired", null);
        repo.insert(def, "system:seed");
        repo.setStatus(def.name(), def.version(), "live", 0);
        repo.setRollout(def.name(), def.version(), Map.of("mode", "live", "by", "system:seed", "reason", "seed"), null);
        events.append("config", "decision:" + def.name(), new NewEvent("decision.definition.published",
            Map.of("decision", def.name(), "version", def.version(), "rollout", "live", "reason", "seed", "tier", def.tier().name()), Actor.system("seed")));
        cache.invalidate(def.name());
        return def;
    }
}
