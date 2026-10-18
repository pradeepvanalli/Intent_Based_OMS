package ai.intentoms.order.contact;

import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /api/v1/orders/{id}/contacts} (OC-03): help desks, chat and phone systems report that a customer got in
 * touch about an order. Body: {@code {"reason": "wismo"|"complaint"|..., "channel": "chat", "source": "zendesk",
 * "externalId": "ticket-123"}}. Returns 201 when recorded, 200 with {@code duplicate: true} when the externalId was
 * already recorded for this order.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderContactController {
    private final CustomerContactService contacts;

    public OrderContactController(CustomerContactService contacts) { this.contacts = contacts; }

    public record ContactRequest(String reason, String channel, String source, String externalId) {}

    @PostMapping("/{id}/contacts")
    @RequiresPermission(Permissions.ORDER_MODIFY)
    public ResponseEntity<Map<String, Object>> contact(@PathVariable String id, @RequestBody(required = false) ContactRequest req) {
        ContactRequest r = req == null ? new ContactRequest("other", null, null, null) : req;
        Map<String, Object> out = contacts.record(id, new CustomerContactService.Contact(r.reason(), r.channel(), r.source(), r.externalId()));
        return ResponseEntity.status(Boolean.TRUE.equals(out.get("recorded")) ? HttpStatus.CREATED : HttpStatus.OK).body(out);
    }
}
