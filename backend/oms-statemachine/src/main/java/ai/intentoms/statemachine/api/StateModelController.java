package ai.intentoms.statemachine.api;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.MigrationReport;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.ValidationReport;
import ai.intentoms.statemachine.store.StateModelRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST surface for state models (FR-SM-06, FR-SM-09, FR-SM-10): list, read, validate, publish, migrate. */
@RestController
@RequestMapping("/api/v1/state-models")
public class StateModelController {
    private final StateModelRegistry registry;
    private final StateModelRepository repo;

    public StateModelController(StateModelRegistry registry, StateModelRepository repo) {
        this.registry = registry;
        this.repo = repo;
    }

    public record ModelSummary(String modelId, int latestVersion, String entity, boolean quantityLevel, int states, int transitions, List<Integer> versions) {}
    public record ValidateRequest(@NotBlank String yaml) {}
    public record PublishRequest(@NotBlank String yaml, @NotBlank String reason) {}
    public record MigrateRequest(int fromVersion, int toVersion, boolean dryRun) {}

    @GetMapping
    @RequiresPermission({Permissions.STATEMODEL_EDIT, Permissions.STATEMODEL_PUBLISH, Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public List<ModelSummary> list() {
        List<ModelSummary> out = new ArrayList<>();
        for (String id : registry.modelIds()) {
            List<StateModel> versions = registry.versions(id);
            StateModel latest = versions.get(versions.size() - 1);
            out.add(new ModelSummary(id, latest.version(), latest.entity(), latest.quantityLevel(), latest.states().size(), latest.transitions().size(),
                versions.stream().map(StateModel::version).toList()));
        }
        return out;
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.STATEMODEL_EDIT, Permissions.STATEMODEL_PUBLISH, Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public Map<String, Object> get(@PathVariable String id) {
        StateModel latest = registry.latest(id);
        Map<String, Object> out = new LinkedHashMap<>(latest.toMap());
        out.put("yaml", latest.yaml());
        out.put("versions", repo.publishInfos(id));
        return out;
    }

    @GetMapping("/{id}/versions/{v}")
    @RequiresPermission({Permissions.STATEMODEL_EDIT, Permissions.STATEMODEL_PUBLISH, Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public Map<String, Object> version(@PathVariable String id, @PathVariable int v) {
        StateModel m = registry.get(id, v);
        Map<String, Object> out = new LinkedHashMap<>(m.toMap());
        out.put("yaml", m.yaml());
        repo.publishInfos(id).stream().filter(p -> p.version() == v).findFirst().ifPresent(p -> {
            out.put("publishedAt", p.publishedAt());
            out.put("publishedBy", p.publishedBy());
            out.put("reason", p.reason());
        });
        return out;
    }

    @PostMapping("/validate")
    @RequiresPermission({Permissions.STATEMODEL_EDIT, Permissions.STATEMODEL_PUBLISH})
    public ValidationReport validate(@Valid @RequestBody ValidateRequest req) { return registry.validate(req.yaml()); }

    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.STATEMODEL_PUBLISH)
    public Map<String, Object> publish(@Valid @RequestBody PublishRequest req) {
        StateModel m = registry.publish(req.yaml(), req.reason());
        Map<String, Object> out = new LinkedHashMap<>(m.toMap());
        out.put("warnings", registry.validate(req.yaml()).warnings());
        return out;
    }

    @PostMapping("/{id}/migrate")
    @RequiresPermission(Permissions.STATEMODEL_PUBLISH)
    public MigrationReport migrate(@PathVariable String id, @RequestBody MigrateRequest req) {
        if (!registry.exists(id, req.fromVersion())) throw new NotFoundException("State model " + id + " version " + req.fromVersion() + " not found");
        return registry.migrate(id, req.fromVersion(), req.toVersion(), req.dryRun());
    }
}
