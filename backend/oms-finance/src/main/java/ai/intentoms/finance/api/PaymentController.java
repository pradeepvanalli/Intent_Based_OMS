package ai.intentoms.finance.api;

import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read access to payments (FR-FIN-01); mutations happen through the order workflow, not this API. */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) { this.payments = payments; }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public List<Payment> forOrder(@RequestParam String orderId) { return payments.forOrder(orderId); }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.ORDER_READ)
    public Payment get(@PathVariable String id) { return payments.get(id); }
}
