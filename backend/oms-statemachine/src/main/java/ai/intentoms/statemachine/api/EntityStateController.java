package ai.intentoms.statemachine.api;

import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.TransitionDef;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for entity status and transitions (FR-SM-02, FR-SM-03). Firing requires {@code order.modify}; the
 * transition's own permission is additionally enforced by the engine for {@code api.*} triggers.
 */
@RestController
@RequestMapping("/api/v1/entities/{type}/{id}")
public class EntityStateController {
    private final StateMachine machine;

    public EntityStateController(StateMachine machine) { this.machine = machine; }

    public record FireRequest(@NotBlank String trigger, Integer qty, String fromState, Map<String, Object> vars, String correlationId, String causationId) {}

    @GetMapping("/status")
    @RequiresPermission(Permissions.ORDER_READ)
    public EntityStatus status(@PathVariable String type, @PathVariable String id) { return machine.status(type, id); }

    @GetMapping("/transitions")
    @RequiresPermission(Permissions.ORDER_READ)
    public List<TransitionDef> available(@PathVariable String type, @PathVariable String id, @RequestParam(required = false) Map<String, String> query) {
        return machine.available(type, id, query == null ? Map.of() : Map.copyOf(query));
    }

    @PostMapping("/transitions")
    @RequiresPermission({Permissions.ORDER_MODIFY, Permissions.ORDER_CANCEL, Permissions.ORDER_RELEASE})
    public TransitionResult fire(@PathVariable String type, @PathVariable String id, @Valid @RequestBody FireRequest req) {
        return machine.fire(new TransitionRequest(type, id, req.trigger(), req.qty(), req.fromState(), req.vars(), null, req.correlationId(), req.causationId()));
    }
}
