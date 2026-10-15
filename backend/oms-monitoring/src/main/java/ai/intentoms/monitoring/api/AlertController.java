package ai.intentoms.monitoring.api;

import ai.intentoms.monitoring.alert.AlertRecord;
import ai.intentoms.monitoring.alert.AlertService;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alert history (FR-MON-06), newest episode first. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertService alerts;

    public AlertController(AlertService alerts) { this.alerts = alerts; }

    @GetMapping
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.ANALYTICS_READ, Permissions.CONFIG_ADMIN})
    public Page<AlertRecord> list(@RequestParam(required = false) String severity, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return alerts.list(severity, offset, limit);
    }
}
