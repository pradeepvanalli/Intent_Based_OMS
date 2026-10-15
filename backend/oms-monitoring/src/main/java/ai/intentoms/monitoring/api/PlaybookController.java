package ai.intentoms.monitoring.api;

import ai.intentoms.monitoring.playbook.Playbook;
import ai.intentoms.monitoring.playbook.PlaybookService;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Playbook catalogue (FR-MON-04): read for exception workers, write for config admins. */
@RestController
@RequestMapping("/api/v1/playbooks")
public class PlaybookController {
    private final PlaybookService playbooks;
    private final AuthorizationService authz;

    public PlaybookController(PlaybookService playbooks, AuthorizationService authz) {
        this.playbooks = playbooks;
        this.authz = authz;
    }

    public record PlaybookBody(String description, List<Map<String, Object>> steps) {}

    @GetMapping
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.EXCEPTION_WORK, Permissions.CONFIG_ADMIN})
    public List<Playbook> list() { return playbooks.list(); }

    @GetMapping("/{name}")
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.EXCEPTION_WORK, Permissions.CONFIG_ADMIN})
    public Playbook get(@PathVariable String name) { return playbooks.get(name); }

    @PutMapping("/{name}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Playbook put(@PathVariable String name, @RequestBody PlaybookBody body) {
        return playbooks.save(new Playbook(name, body.description(), body.steps()), Actor.parse(authz.actorRef()));
    }

    /** Action names with a registered handler and the tenant's spend limits — lets the UI flag steps that would be skipped. */
    @GetMapping("/-/actions")
    @RequiresPermission({Permissions.EXCEPTION_READ, Permissions.EXCEPTION_WORK, Permissions.CONFIG_ADMIN})
    public Map<String, Object> actions() { return Map.of("actions", playbooks.registeredActions(), "spendLimits", playbooks.spendLimits()); }
}
