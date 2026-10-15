package ai.intentoms.copilot.bootstrap;

import ai.intentoms.copilot.change.ChangeKind;
import ai.intentoms.copilot.change.ChangeRequest;
import ai.intentoms.copilot.change.ChangeSet;
import ai.intentoms.copilot.change.ChangeSetSchema;
import ai.intentoms.copilot.change.ConfigCopilotService;
import ai.intentoms.copilot.change.GeneratedTest;
import ai.intentoms.copilot.change.ProposedChange;
import ai.intentoms.copilot.change.ValidationResult;
import ai.intentoms.copilot.change.CatalogueContextBuilder;
import ai.intentoms.copilot.config.CopilotProperties;
import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.Untrusted;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DefinitionEditor;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.StateModelRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Bootstrap copilot (UC-11, FR-CFG-01): "describe your business" → a reviewable configuration bundle. With a model key
 * the blueprint is proposed by the model over the starter blueprint and catalogue; without one the deterministic
 * {@code starter-blueprint.yaml} is filled from the structured fields. Either way the result is a {@link ChangeSet}
 * validated and staged through {@link ConfigCopilotService}, so nothing is published (FR-CFG-02, GR-14).
 */
@Service
public class BootstrapCopilotService {
    private static final Logger log = LoggerFactory.getLogger(BootstrapCopilotService.class);
    private final ConfigCopilotService changes;
    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final CatalogueContextBuilder catalogue;
    private final DecisionDefinitionRepository decisions;
    private final StateModelRegistry stateModels;
    private final CopilotProperties props;

    public BootstrapCopilotService(ConfigCopilotService changes, CopilotLlmClient llm, PromptTemplates templates, CatalogueContextBuilder catalogue, DecisionDefinitionRepository decisions,
                                   StateModelRegistry stateModels, CopilotProperties props) {
        this.changes = changes;
        this.llm = llm;
        this.templates = templates;
        this.catalogue = catalogue;
        this.decisions = decisions;
        this.stateModels = stateModels;
        this.props = props;
    }

    /** Builds the blueprint, persists it as a ChangeSet (source {@code bootstrap}) and, when asked, stages every valid change. */
    public ChangeSet propose(BusinessDescription d) {
        TenantBlueprint bp = blueprint(d);
        ChangeRequest req = new ChangeRequest(d.text().isBlank() ? "Bootstrap tenant configuration for channels " + d.channels() : d.text(), "bootstrap", Map.of("channels", d.channels(), "categories", d.categories()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("blueprintSections", bp.sections());
        meta.put("generated", bp.generated());
        if (bp.generated()) meta.put("model", llm.model());
        ChangeSet cs = changes.proposeFromChanges(req, bp.summary(), bp.assumptions(), bp.questions(), bp.changes(), "bootstrap", meta);
        if (d.stage() && cs.changes().stream().anyMatch(c -> c.validation().ok())) cs = changes.stage(cs.id(), List.of());
        return cs;
    }

    /** The blueprint without persisting anything (preview). */
    public TenantBlueprint blueprint(BusinessDescription d) {
        TenantBlueprint starter = StarterBlueprint.render(templates.get("starter-blueprint.yaml"), d, starterDecisions(), starterStateModels());
        if (!llm.available()) return starter;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("businessDescription", Untrusted.text("retailer", d.text()));
            payload.put("structured", Map.of("channels", d.channels(), "nodes", d.nodes(), "categories", d.categories(), "returnWindowDays", d.returnWindowDays(), "restockingFeePct", d.restockingFeePct(),
                "keepItEnabled", d.keepItEnabled(), "marginFloor", d.marginFloor(), "hazmat", d.hazmat(), "ageRestricted", d.ageRestricted(), "international", d.international()));
            payload.put("starterBlueprint", starter.sections());
            payload.put("starterChanges", starter.changes().stream().map(c -> Map.of("id", c.id(), "kind", c.kind().name(), "target", c.target(), "after", c.after())).toList());
            payload.put("catalogue", catalogue.build("bootstrap"));
            JsonNode out = llm.complete("bootstrap", templates.get("system-bootstrap.md") + "\n\n# Artefact formats\n" + catalogue.formats(), payload, ChangeSetSchema.PROPOSAL);
            List<ProposedChange> proposed = new ArrayList<>();
            int i = 1;
            for (JsonNode n : out.path("changes")) {
                List<GeneratedTest> tests = new ArrayList<>();
                for (JsonNode t : n.path("tests")) tests.add(new GeneratedTest(t.path("caseId").asText("case_" + tests.size()), safeMap(t.path("input").asText("{}")), safeMap(t.path("expected").asText("{}")), t.path("notes").asText(null)));
                proposed.add(new ProposedChange(n.path("id").asText("b" + i++), ChangeKind.parse(n.path("kind").asText()), n.path("target").asText(), n.path("rationale").asText(), null, n.path("after").asText(), null,
                    ValidationResult.passed(), tests, n.path("riskTier").asText("T1"), 1, false, null, null));
            }
            // Model proposals replace starter artefacts of the same kind+target; untouched starter artefacts are kept so the bundle stays complete.
            Map<String, ProposedChange> merged = new LinkedHashMap<>();
            for (ProposedChange c : starter.changes()) merged.put(c.kind() + ":" + c.target(), c);
            for (ProposedChange c : proposed) merged.put(c.kind() + ":" + c.target(), c);
            List<String> assumptions = new ArrayList<>();
            out.path("assumptions").forEach(a -> assumptions.add(a.asText()));
            List<String> questions = new ArrayList<>();
            out.path("questions").forEach(q -> questions.add(q.asText()));
            return new TenantBlueprint(out.path("summary").asText(starter.summary()), assumptions, questions, starter.sections(), new ArrayList<>(merged.values()), true);
        } catch (OmsException e) {
            log.warn("bootstrap blueprint fell back to the starter template: {}", e.getMessage());
            return starter;
        }
    }

    /** Starter decision YAMLs: the tenant's own when present, else the files of the starter tenant directory. */
    private Map<String, String> starterDecisions() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : decisions.names()) {
            List<DecisionDefinitionRepository.Row> versions = decisions.versions(name);
            if (!versions.isEmpty()) out.put(name, versions.stream().filter(r -> "live".equals(r.definition().status())).findFirst().orElse(versions.get(0)).definition().yaml());
        }
        Path dir = Path.of(props.starterDir(), "decisions").toAbsolutePath().normalize();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList()) {
                    String yaml = Files.readString(f);
                    Object name = Json.yamlMap(yaml).get("decision");
                    if (name != null) out.putIfAbsent(String.valueOf(name), yaml);
                }
            } catch (IOException e) { log.warn("cannot read starter decisions from {}: {}", dir, e.getMessage()); }
        }
        return out;
    }

    /** Starter state models not yet published for the tenant, keyed by model id. */
    private Map<String, String> starterStateModels() {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> existing = stateModels.modelIds();
        Path dir = Path.of(props.starterDir(), "state-models").toAbsolutePath().normalize();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList()) {
                    String yaml = Files.readString(f);
                    Object id = Json.yamlMap(yaml).get("stateModel");
                    if (id != null && !existing.contains(String.valueOf(id))) out.put(String.valueOf(id), DefinitionEditor.apply(yaml, Map.of("version", 1)));
                }
            } catch (IOException e) { log.warn("cannot read starter state models from {}: {}", dir, e.getMessage()); }
        }
        return out;
    }

    private static Map<String, Object> safeMap(String json) {
        try { return Json.readMap(json); } catch (IllegalArgumentException e) { return new LinkedHashMap<>(Map.of("raw", json)); }
    }
}
