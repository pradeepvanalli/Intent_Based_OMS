package ai.intentoms.copilot.api;

import ai.intentoms.copilot.explain.ExplainService;
import ai.intentoms.copilot.explain.Explanation;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explain copilot endpoint (FR-XAI-03/04). */
@RestController
@RequestMapping("/api/v1/copilot/explain")
public class ExplainController {
    private final ExplainService explain;

    public ExplainController(ExplainService explain) { this.explain = explain; }

    @GetMapping("/{recordId}")
    @RequiresPermission(Permissions.DECISION_READ)
    public Explanation explain(@PathVariable String recordId, @RequestParam(defaultValue = "internal") String audience) { return explain.explain(recordId, audience); }
}
