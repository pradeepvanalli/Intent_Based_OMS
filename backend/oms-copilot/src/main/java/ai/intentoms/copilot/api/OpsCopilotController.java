package ai.intentoms.copilot.api;

import ai.intentoms.copilot.ops.OpsAnswer;
import ai.intentoms.copilot.ops.OpsCopilotService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Ops copilot endpoints (FR-MON-08). */
@RestController
@RequestMapping("/api/v1/copilot/ops")
public class OpsCopilotController {
    private final OpsCopilotService ops;

    public OpsCopilotController(OpsCopilotService ops) { this.ops = ops; }

    public record AskBody(String question) {}

    @GetMapping("/templates")
    @RequiresPermission({Permissions.ANALYTICS_READ, Permissions.ORDER_READ})
    public List<Map<String, Object>> templates() { return ops.templates(); }

    @PostMapping("/ask")
    @RequiresPermission({Permissions.ANALYTICS_READ, Permissions.ORDER_READ})
    public OpsAnswer ask(@RequestBody AskBody body) { return ops.ask(body.question()); }
}
