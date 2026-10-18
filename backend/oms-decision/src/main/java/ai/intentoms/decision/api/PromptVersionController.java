package ai.intentoms.decision.api;

import ai.intentoms.decision.studio.PromptVersion;
import ai.intentoms.decision.studio.PromptVersionService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** DF-06 REST surface: prompt versions of a decision's questions, through the studio's gate. */
@RestController
@RequestMapping("/api/v1/decisions/{name}/prompts")
public class PromptVersionController {
    private final PromptVersionService prompts;

    public PromptVersionController(PromptVersionService prompts) { this.prompts = prompts; }

    public record DraftRequest(String question, String text, String reason, String ticket) {}
    public record SubmitRequest(String reason, String ticket) {}
    public record PublishRequest(String rollout) {}

    @GetMapping
    @RequiresPermission(Permissions.DECISION_READ)
    public List<PromptVersion> list(@PathVariable String name) { return prompts.list(name); }

    @GetMapping("/{question}/{version}")
    @RequiresPermission(Permissions.DECISION_READ)
    public PromptVersion get(@PathVariable String name, @PathVariable String question, @PathVariable int version) { return prompts.get(name, question, version); }

    @PostMapping
    @RequiresPermission(Permissions.DECISION_EDIT)
    public PromptVersion draft(@PathVariable String name, @RequestBody DraftRequest body) {
        return prompts.draft(name, body.question(), body.text(), body.reason(), body.ticket());
    }

    @PostMapping("/{question}/{version}/lint")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public Map<String, Object> lint(@PathVariable String name, @PathVariable String question, @PathVariable int version) { return prompts.lint(name, question, version); }

    @PostMapping("/{question}/{version}/evaluate")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public PromptVersion evaluate(@PathVariable String name, @PathVariable String question, @PathVariable int version) { return prompts.evaluate(name, question, version); }

    @PostMapping("/{question}/{version}/submit")
    @RequiresPermission(Permissions.DECISION_EDIT)
    public PromptVersion submit(@PathVariable String name, @PathVariable String question, @PathVariable int version, @RequestBody(required = false) SubmitRequest body) {
        return prompts.submit(name, question, version, body == null ? null : body.reason(), body == null ? null : body.ticket());
    }

    @PostMapping("/{question}/{version}/approve")
    @RequiresPermission(Permissions.DECISION_APPROVE)
    public PromptVersion approve(@PathVariable String name, @PathVariable String question, @PathVariable int version) { return prompts.approve(name, question, version); }

    @PostMapping("/{question}/{version}/publish")
    @RequiresPermission(Permissions.DECISION_PUBLISH)
    public PromptVersion publish(@PathVariable String name, @PathVariable String question, @PathVariable int version, @RequestBody(required = false) PublishRequest body) {
        return prompts.publish(name, question, version, body == null ? null : body.rollout());
    }

    @PostMapping("/{question}/{version}/reject")
    @RequiresPermission(Permissions.DECISION_APPROVE)
    public PromptVersion reject(@PathVariable String name, @PathVariable String question, @PathVariable int version) { return prompts.reject(name, question, version); }
}
