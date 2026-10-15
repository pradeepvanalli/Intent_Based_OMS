package ai.intentoms.workflow.api;

import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.workflow.bulk.BulkOperation;
import ai.intentoms.workflow.bulk.BulkOperationService;
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

/** Bulk operations (FR-WF-07); all endpoints need {@code order.bulk}. */
@RestController
@RequestMapping("/api/v1/bulk-operations")
@RequiresPermission(Permissions.ORDER_BULK)
public class BulkOperationController {
    private final BulkOperationService bulk;

    public BulkOperationController(BulkOperationService bulk) { this.bulk = bulk; }

    public record SubmitRequest(String action, List<String> subjectIds, Map<String, Object> params, Boolean dryRun, Integer perSecond) {}

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BulkOperation submit(@RequestBody SubmitRequest r) {
        return bulk.submit(r.action(), r.subjectIds(), r.params(), Boolean.TRUE.equals(r.dryRun()), r.perSecond() == null ? 0 : r.perSecond());
    }

    @GetMapping
    public Page<BulkOperation> list(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) { return bulk.list(offset, limit); }

    @GetMapping("/actions")
    public List<String> actions() { return bulk.actions(); }

    @GetMapping("/{id}")
    public BulkOperation get(@PathVariable String id) { return bulk.get(id); }

    @PostMapping("/{id}/rollback")
    public BulkOperation rollback(@PathVariable String id) { return bulk.rollback(id); }
}
