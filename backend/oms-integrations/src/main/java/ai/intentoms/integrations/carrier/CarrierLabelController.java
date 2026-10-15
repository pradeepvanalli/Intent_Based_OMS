package ai.intentoms.integrations.carrier;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/shipments/{id}/label} ({@code order.read}): the aggregator label bought for a shipment (URL, rate, provider ids). */
@RestController
@RequestMapping("/api/v1/shipments")
public class CarrierLabelController {
    private final CarrierLabelRepository labels;

    public CarrierLabelController(CarrierLabelRepository labels) { this.labels = labels; }

    @GetMapping("/{id}/label")
    @RequiresPermission(Permissions.ORDER_READ)
    public CarrierLabelRepository.Label label(@PathVariable String id) {
        return labels.forShipment(id).orElseThrow(() -> new NotFoundException("No carrier label for shipment " + id));
    }
}
