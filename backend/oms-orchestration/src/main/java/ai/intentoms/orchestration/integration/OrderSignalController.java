package ai.intentoms.orchestration.integration;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /api/v1/orders/{id}/signals {name, payload}} (FR-WF-05, ops tooling): pushes a signal such as
 * {@code cancel_requested}, {@code inventory_available}, {@code hold_released} or {@code agent_verified} to the order's
 * workflow. Requires {@code order.modify}; optional {@code Idempotency-Key} de-duplicates retries.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderSignalController {
    public record SignalRequest(String name, Map<String, Object> payload) {}

    private final InboundEventService events;

    public OrderSignalController(InboundEventService events) { this.events = events; }

    @PostMapping("/{id}/signals")
    @RequiresPermission(Permissions.ORDER_MODIFY)
    public Map<String, Object> signal(@PathVariable String id, @RequestBody SignalRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (req == null || req.name() == null || req.name().isBlank()) throw new ValidationException("signal name is required");
        return events.signal(id, req.name().trim(), req.payload(), idempotencyKey);
    }
}
