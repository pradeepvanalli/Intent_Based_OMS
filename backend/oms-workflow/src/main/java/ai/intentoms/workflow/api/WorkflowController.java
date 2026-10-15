package ai.intentoms.workflow.api;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.workflow.model.TimelineEntry;
import ai.intentoms.workflow.model.WorkflowEngine;
import ai.intentoms.workflow.model.WorkflowView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Workflow instance views, signals and ops actions (FR-WF-05, FR-WF-06). Views need {@code order.read}; mutations {@code order.modify}. */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {
    private final WorkflowEngine engine;

    public WorkflowController(WorkflowEngine engine) { this.engine = engine; }

    /** Body of {@code POST /{id}/signal}; {@code idempotencyKey} falls back to the {@code Idempotency-Key} header. */
    public record SignalRequest(String name, Map<String, Object> payload, String idempotencyKey) {}

    public record ReasonRequest(String reason) {}

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.ORDER_READ)
    public WorkflowView get(@PathVariable String id) { return engine.get(id); }

    @GetMapping("/{id}/timeline")
    @RequiresPermission(Permissions.ORDER_READ)
    public List<TimelineEntry> timeline(@PathVariable String id) { return engine.timeline(id); }

    @GetMapping("/by-key/{type}/{key}")
    @RequiresPermission(Permissions.ORDER_READ)
    public WorkflowView byKey(@PathVariable String type, @PathVariable String key) {
        return engine.find(type, key).orElseThrow(() -> new NotFoundException("No workflow " + type + " for business key " + key));
    }

    @PostMapping("/{id}/signal")
    @RequiresPermission(Permissions.ORDER_MODIFY)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void signal(@PathVariable String id, @RequestBody SignalRequest body, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        String key = body.idempotencyKey() != null ? body.idempotencyKey() : idempotencyKey;
        engine.signalById(id, body.name(), body.payload(), key);
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(Permissions.ORDER_MODIFY)
    public WorkflowView cancel(@PathVariable String id, @RequestBody(required = false) ReasonRequest body) {
        engine.cancel(id, body == null ? null : body.reason());
        return engine.get(id);
    }

    @PostMapping("/{id}/retry")
    @RequiresPermission(Permissions.ORDER_MODIFY)
    public WorkflowView retry(@PathVariable String id) {
        engine.retryNow(id);
        return engine.get(id);
    }
}
