package ai.intentoms.statemachine.api;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/** REST surface for the hold overlay (FR-SM-07). */
@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {
    private final HoldService holds;

    public HoldController(HoldService holds) { this.holds = holds; }

    /** @param ttl ISO-8601 duration (e.g. PT24H); null = no expiry */
    public record PlaceRequest(@NotBlank String type, @NotBlank String scopeType, @NotBlank String scopeId, @NotEmpty Set<String> blocks, String reasonCode, String note, String ttl) {}
    public record ReleaseRequest(String resolution) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.ORDER_HOLD)
    public Hold place(@Valid @RequestBody PlaceRequest req) {
        Duration ttl = null;
        if (req.ttl() != null && !req.ttl().isBlank()) {
            try { ttl = Duration.parse(req.ttl()); } catch (DateTimeParseException e) { throw new ValidationException("ttl must be an ISO-8601 duration such as PT24H"); }
        }
        return holds.place(req.type(), req.scopeType(), req.scopeId(), req.blocks(), req.reasonCode(), req.note(), ttl, null);
    }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public List<Hold> byScope(@RequestParam String scopeType, @RequestParam String scopeId) { return holds.active(scopeType, scopeId); }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.ORDER_READ)
    public Hold get(@PathVariable String id) { return holds.get(id); }

    @PostMapping("/{id}/release")
    @RequiresPermission(Permissions.ORDER_RELEASE)
    public Hold release(@PathVariable String id, @RequestBody(required = false) ReleaseRequest req) {
        return holds.release(id, req == null ? null : req.resolution(), null);
    }
}
