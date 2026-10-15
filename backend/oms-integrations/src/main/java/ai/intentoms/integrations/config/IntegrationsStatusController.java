package ai.intentoms.integrations.config;

import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** {@code GET /api/v1/integrations/status} ({@code config.admin}): active adapters and whether they are configured; never reveals secrets. */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationsStatusController {
    private final IntegrationsStatus status;

    public IntegrationsStatusController(IntegrationsStatus status) { this.status = status; }

    @GetMapping("/status")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> status() { return status.snapshot(); }
}
