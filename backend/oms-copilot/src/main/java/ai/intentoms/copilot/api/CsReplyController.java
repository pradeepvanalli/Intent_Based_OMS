package ai.intentoms.copilot.api;

import ai.intentoms.copilot.cs.CsDraft;
import ai.intentoms.copilot.cs.CsReplyService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CS reply copilot endpoint (FR-CS-05, GR-15). */
@RestController
@RequestMapping("/api/v1/copilot/cs")
public class CsReplyController {
    private final CsReplyService cs;

    public CsReplyController(CsReplyService cs) { this.cs = cs; }

    public record DraftBody(String orderId, String message, String intent) {}

    @PostMapping("/draft")
    @RequiresPermission(Permissions.ORDER_READ)
    public CsDraft draft(@RequestBody DraftBody body) { return cs.draft(body.orderId(), body.message(), body.intent()); }
}
