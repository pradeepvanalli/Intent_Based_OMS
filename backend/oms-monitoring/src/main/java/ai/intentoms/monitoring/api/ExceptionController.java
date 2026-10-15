package ai.intentoms.monitoring.api;

import ai.intentoms.monitoring.exception.ExceptionQuery;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionRequest;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.monitoring.exception.ExceptionTypeDef;
import ai.intentoms.monitoring.playbook.PlaybookService;
import ai.intentoms.monitoring.playbook.PlaybookStep;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Exceptions inbox (UI contract item 10, FR-MON-03/04): {@code exception.read} to list/get, {@code exception.work} to raise, assign, resolve and run playbooks. */
@RestController
@RequestMapping("/api/v1")
public class ExceptionController {
    private final ExceptionService exceptions;
    private final PlaybookService playbooks;

    public ExceptionController(ExceptionService exceptions, PlaybookService playbooks) {
        this.exceptions = exceptions;
        this.playbooks = playbooks;
    }

    public record RaiseRequest(String source, String code, String message, String orderId, String lineId, String nodeId, Map<String, Object> details, String type) {}
    public record AssignRequest(String assignee) {}
    public record ResolveRequest(String resolution, String note) {}

    @GetMapping("/exceptions")
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.EXCEPTION_WORK})
    public Page<ExceptionRecord> list(@RequestParam(required = false) String status, @RequestParam(required = false) String type, @RequestParam(required = false) String severity,
                                      @RequestParam(required = false) String queue, @RequestParam(required = false) String orderId, @RequestParam(required = false) String assignee,
                                      @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return exceptions.list(new ExceptionQuery(status, type, severity, queue, orderId, assignee, offset, limit));
    }

    @GetMapping("/exceptions/{id}")
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.EXCEPTION_WORK})
    public ExceptionRecord get(@PathVariable String id) { return exceptions.get(id); }

    @PostMapping("/exceptions")
    @RequiresPermission(Permissions.EXCEPTION_WORK)
    @ResponseStatus(HttpStatus.CREATED)
    public ExceptionRecord raise(@RequestBody RaiseRequest r) {
        return exceptions.raise(new ExceptionRequest(r.source() == null ? "manual" : r.source(), r.code() == null ? "manual" : r.code(), r.message(), r.orderId(), r.lineId(), r.nodeId(), r.details()), r.type());
    }

    @PostMapping("/exceptions/{id}/assign")
    @RequiresPermission(Permissions.EXCEPTION_WORK)
    public ExceptionRecord assign(@PathVariable String id, @RequestBody AssignRequest body) { return exceptions.assign(id, body.assignee()); }

    @PostMapping("/exceptions/{id}/resolve")
    @RequiresPermission(Permissions.EXCEPTION_WORK)
    public ExceptionRecord resolve(@PathVariable String id, @RequestBody ResolveRequest body) { return exceptions.resolve(id, body.resolution(), body.note()); }

    @PostMapping("/exceptions/{id}/run-playbook")
    @RequiresPermission(Permissions.EXCEPTION_WORK)
    public List<PlaybookStep> runPlaybook(@PathVariable String id) { return playbooks.rerun(id); }

    @GetMapping("/exception-types")
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.EXCEPTION_WORK, Permissions.CONFIG_ADMIN})
    public List<ExceptionTypeDef> types() { return exceptions.taxonomy(); }
}
