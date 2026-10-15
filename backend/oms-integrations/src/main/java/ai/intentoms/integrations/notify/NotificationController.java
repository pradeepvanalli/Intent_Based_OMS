package ai.intentoms.integrations.notify;

import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@code GET /api/v1/notifications?orderId=…} ({@code order.read}): the provider attempts behind an order's communications. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationLogRepository log;

    public NotificationController(NotificationLogRepository log) { this.log = log; }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public List<NotificationLogRepository.Entry> list(@RequestParam(required = false) String orderId, @RequestParam(defaultValue = "100") int limit) {
        return orderId == null || orderId.isBlank() ? log.recent(limit) : log.forOrder(orderId);
    }
}
