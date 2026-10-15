package ai.intentoms.order.api;

import ai.intentoms.order.model.OrderType;
import ai.intentoms.order.service.OrderTypeRegistry;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Order types (FR-CAP-03): list for the UI, upsert for administrators. */
@RestController
@RequestMapping("/api/v1/order-types")
public class OrderTypeController {
    private final OrderTypeRegistry registry;

    public OrderTypeController(OrderTypeRegistry registry) { this.registry = registry; }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public List<OrderType> all() { return registry.all(); }

    @GetMapping("/{name}")
    @RequiresPermission(Permissions.ORDER_READ)
    public OrderType get(@PathVariable String name) { return registry.get(name); }

    @PutMapping("/{name}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public OrderType upsert(@PathVariable String name, @RequestBody OrderType type) {
        return registry.upsert(new OrderType(name, type.description(), type.headerStateModel(), type.lineStateModel(), type.workflowType(), type.decisions(), type.channelDefaults(), type.returnWindowDays(), type.sourcing()));
    }
}
