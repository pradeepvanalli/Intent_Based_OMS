package ai.intentoms.monitoring.api;

import ai.intentoms.monitoring.sla.SlaDefinition;
import ai.intentoms.monitoring.sla.SlaDefinitionService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** SLA definitions (FR-MON-02): {@code PUT} upserts on (orderType, entity, state); durations are ISO-8601 ({@code PT4H}). */
@RestController
@RequestMapping("/api/v1/sla-definitions")
public class SlaDefinitionController {
    private final SlaDefinitionService slas;

    public SlaDefinitionController(SlaDefinitionService slas) { this.slas = slas; }

    public record SlaBody(String orderType, String entity, String state, String maxAge, String onBreach, String expectedNextEventWithin) {}

    @GetMapping
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.ANALYTICS_READ, Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public List<SlaDefinition> list() { return slas.list(); }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.ANALYTICS_READ, Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public SlaDefinition get(@PathVariable String id) { return slas.get(id); }

    @PutMapping
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public SlaDefinition put(@RequestBody SlaBody b) {
        return slas.upsert(new SlaDefinition(null, b.orderType(), b.entity(), b.state(), b.maxAge(), b.onBreach(), b.expectedNextEventWithin(), null));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { slas.delete(id); }
}
