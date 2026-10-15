package ai.intentoms.decision.api;

import ai.intentoms.decision.model.CustomerSafeTraceView;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionTrace;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Decision records, explainability (FR-XAI-01/04), labels (FR-DEC-10) and overrides (GR-13). */
@RestController
@RequestMapping("/api/v1/decision-records")
public class DecisionRecordController {
    private final DecisionService decisions;

    public DecisionRecordController(DecisionService decisions) { this.decisions = decisions; }

    public record LabelRequest(String label, Map<String, Object> details) {}
    public record OverrideRequest(Object value, String reason) {}

    @GetMapping
    @RequiresPermission(Permissions.DECISION_READ)
    public List<DecisionRecord> forSubject(@RequestParam String subjectType, @RequestParam String subjectId) { return decisions.forSubject(subjectType, subjectId); }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.DECISION_READ)
    public DecisionRecord get(@PathVariable String id) { return decisions.record(id); }

    /** Full trace, or the customer-safe variant with {@code ?audience=customer} (FR-XAI-04). */
    @GetMapping("/{id}/why")
    @RequiresPermission(Permissions.DECISION_READ)
    public Object why(@PathVariable String id, @RequestParam(required = false) String audience) {
        DecisionTrace trace = decisions.why(id);
        return "customer".equalsIgnoreCase(audience) ? CustomerSafeTraceView.from(trace) : trace;
    }

    @PostMapping("/{id}/label")
    @RequiresPermission(Permissions.DECISION_READ)
    public DecisionRecord label(@PathVariable String id, @RequestBody LabelRequest body) {
        decisions.label(id, body.label(), body.details());
        return decisions.record(id);
    }

    @PostMapping("/{id}/override")
    @RequiresPermission(Permissions.DECISION_OVERRIDE)
    public DecisionRecord override(@PathVariable String id, @RequestBody OverrideRequest body) { return decisions.override(id, body.value(), body.reason()); }
}
