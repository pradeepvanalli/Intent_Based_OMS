package ai.intentoms.copilot.api;

import ai.intentoms.copilot.bootstrap.BootstrapCopilotService;
import ai.intentoms.copilot.bootstrap.BusinessDescription;
import ai.intentoms.copilot.bootstrap.TenantBlueprint;
import ai.intentoms.copilot.change.ChangeRequest;
import ai.intentoms.copilot.change.ChangeSet;
import ai.intentoms.copilot.change.ConfigCopilotService;
import ai.intentoms.copilot.change.DraftArtifact;
import ai.intentoms.copilot.change.DraftArtifactService;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Configuration and bootstrap copilot endpoints (FR-CFG-01/02, FR-SM-12, UC-11) plus staged artefacts and status.
 * Proposing and staging need decision.edit | statemodel.edit | config.admin; applying an artefact needs the owning
 * permission, checked in the service (statemodel.publish or config.admin).
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class ConfigCopilotController {
    private final ConfigCopilotService changes;
    private final DraftArtifactService artifacts;
    private final BootstrapCopilotService bootstrap;
    private final CopilotStatusService status;

    public ConfigCopilotController(ConfigCopilotService changes, DraftArtifactService artifacts, BootstrapCopilotService bootstrap, CopilotStatusService status) {
        this.changes = changes;
        this.artifacts = artifacts;
        this.bootstrap = bootstrap;
        this.status = status;
    }

    public record StageBody(List<String> changeIds) {}
    public record ReasonBody(String reason) {}

    @PostMapping("/changes")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN})
    public ResponseEntity<ChangeSet> propose(@RequestBody ChangeRequest request) { return ResponseEntity.status(HttpStatus.CREATED).body(changes.propose(request)); }

    @GetMapping("/changes")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN, Permissions.DECISION_READ})
    public Page<ChangeSet> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return changes.list(status, offset, limit);
    }

    @GetMapping("/changes/{id}")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN, Permissions.DECISION_READ})
    public ChangeSet get(@PathVariable String id) { return changes.get(id); }

    @PostMapping("/changes/{id}/stage")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN})
    public ChangeSet stage(@PathVariable String id, @RequestBody(required = false) StageBody body) { return changes.stage(id, body == null ? List.of() : body.changeIds()); }

    @PostMapping("/changes/{id}/reject")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN})
    public ChangeSet reject(@PathVariable String id, @RequestBody ReasonBody body) { return changes.reject(id, body.reason()); }

    @GetMapping("/changes/{id}/changes/{changeId}/explain")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN, Permissions.DECISION_READ})
    public Map<String, Object> explain(@PathVariable String id, @PathVariable String changeId) { return changes.explainChange(id, changeId); }

    @PostMapping("/changes/{id}/changes/{changeId}/tests")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public Map<String, Object> addTests(@PathVariable String id, @PathVariable String changeId) { return changes.addTestsToGoldenSet(id, changeId); }

    @GetMapping("/artifacts")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN, Permissions.STATEMODEL_PUBLISH})
    public List<DraftArtifact> artifacts(@RequestParam(required = false) String status, @RequestParam(required = false) String changesetId) { return artifacts.list(status, changesetId); }

    @GetMapping("/artifacts/{id}")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN, Permissions.STATEMODEL_PUBLISH})
    public DraftArtifact artifact(@PathVariable String id) { return artifacts.get(id); }

    /** Owning permission (statemodel.publish | config.admin) is enforced per artefact kind inside the service. */
    @PostMapping("/artifacts/{id}/apply")
    @RequiresPermission({Permissions.CONFIG_ADMIN, Permissions.STATEMODEL_PUBLISH})
    public DraftArtifact apply(@PathVariable String id, @RequestBody ReasonBody body) { return artifacts.apply(id, body.reason()); }

    @PostMapping({"/artifacts/{id}/reject", "/artifacts/{id}/discard"})
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN})
    public DraftArtifact rejectArtifact(@PathVariable String id, @RequestBody ReasonBody body) { return artifacts.reject(id, body.reason()); }

    @PostMapping("/bootstrap")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN})
    public ResponseEntity<ChangeSet> bootstrap(@RequestBody BusinessDescription description) { return ResponseEntity.status(HttpStatus.CREATED).body(bootstrap.propose(description)); }

    @PostMapping("/bootstrap/preview")
    @RequiresPermission({Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT, Permissions.CONFIG_ADMIN})
    public TenantBlueprint preview(@RequestBody BusinessDescription description) { return bootstrap.blueprint(description); }

    @GetMapping("/status")
    @RequiresPermission({Permissions.DECISION_READ, Permissions.ORDER_READ, Permissions.ANALYTICS_READ, Permissions.CONFIG_ADMIN, Permissions.DECISION_EDIT, Permissions.STATEMODEL_EDIT})
    public Map<String, Object> status() { return status.status(); }

}
