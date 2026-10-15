package ai.intentoms.finance.api;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.finance.payment.Refund;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Refund listing and manual refunds (FR-RET-04, GR-05). A manual refund runs the {@link ai.intentoms.finance.guard.SpendGuard}
 * velocity caps / circuit breaker; a refusal surfaces as a 422 {@code guardrail_violation} problem whose details tell
 * the UI to route the request to human approval.
 */
@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {
    private final PaymentService payments;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final IdempotentRequests idempotent;

    public RefundController(PaymentService payments, AuthorizationService authz, AuditLog audit, IdempotentRequests idempotent) {
        this.payments = payments;
        this.authz = authz;
        this.audit = audit;
        this.idempotent = idempotent;
    }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public List<Refund> forOrder(@RequestParam String orderId) { return payments.refundsForOrder(orderId); }

    /**
     * @param orderId  order to refund
     * @param amount   amount to refund
     * @param currency ISO currency, defaults to USD
     * @param reason   mandatory reason recorded on the refund rows and audit log
     * @param returnId RMA reference when the refund belongs to a return, else null
     */
    public record ManualRefund(String orderId, BigDecimal amount, String currency, String reason, String returnId) {}

    @PostMapping
    @RequiresPermission(Permissions.REFUND_ISSUE)
    public List<Refund> issue(@RequestBody ManualRefund body, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (body.orderId() == null || body.orderId().isBlank()) throw new ValidationException("orderId is required");
        if (body.amount() == null || body.amount().signum() <= 0) throw new ValidationException("amount must be positive");
        if (body.reason() == null || body.reason().isBlank()) throw new ValidationException("reason is required");
        Money amount = new Money(body.amount(), body.currency() == null || body.currency().isBlank() ? "USD" : body.currency());
        Actor actor = Actor.parse(authz.actorRef());
        Refund[] legs = idempotent.execute("finance.refund", idempotencyKey, body, Refund[].class, () -> {
            List<Refund> out = payments.refund(body.orderId(), amount, body.reason(), body.returnId(), actor);
            audit.record("refund.issued", "order", body.orderId(), body.reason(),
                Map.of("amount", amount.amount(), "currency", amount.currency(), "refundIds", out.stream().map(Refund::id).toList()));
            return out.toArray(Refund[]::new);
        });
        return Arrays.asList(legs);
    }
}
