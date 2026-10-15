package ai.intentoms.orchestration.actions;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.finance.payment.Refund;
import ai.intentoms.finance.payment.RefundStatus;
import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.GuardrailViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Playbook {@code refund} (FR-MON-04, FR-FIN-01, GR-05): refunds {@code amount} (step parameter, else the exception's
 * {@code details.refundAmount}, else the order total) on the order's captured tenders, capped by the tenant's
 * {@code refund_usd} playbook limit; the finance {@code SpendGuard} velocity caps still apply and a refusal is recorded as
 * a failed step so the playbook's {@code onFailure} steps can route it to a person.
 */
@Component
public class RefundAction implements PlaybookAction {
    private final PaymentService payments;
    private final ActionSupport support;

    public RefundAction(PaymentService payments, ActionSupport support) {
        this.payments = payments;
        this.support = support;
    }

    @Override public String action() { return "refund"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        Optional<Order> order = support.order(ctx);
        if (order.isEmpty()) return PlaybookStepResult.skipped("no order to refund");
        Order o = order.get();
        double requested = ActionSupport.num(step.get("amount"), ActionSupport.num(ctx.exception().details().get("refundAmount"), o.totals().grandTotal().amount().doubleValue()));
        double limit = ctx.spendLimit("refund_usd").orElse(ActionSupport.num(step.get("spendLimit"), Double.MAX_VALUE));
        if (requested <= 0) return PlaybookStepResult.skipped("nothing to refund");
        if (requested > limit) return PlaybookStepResult.skipped(String.format(java.util.Locale.ROOT, "refund %.2f exceeds the playbook limit %.2f (GR-05); needs a person", requested, limit));
        Money amount = new Money(BigDecimal.valueOf(requested), o.currency());
        String reason = ActionSupport.str(step, "reason", "playbook refund for exception " + ctx.exception().id() + " (" + ctx.exception().type() + ")");
        try {
            List<Refund> legs = payments.refund(o.id(), amount, reason, null, ctx.actor());
            boolean failed = legs.stream().anyMatch(r -> r.status() == RefundStatus.FAILED);
            String detail = "refunded " + amount + " in " + legs.size() + " leg(s)" + (failed ? " (a leg failed at the PSP)" : "");
            return failed ? PlaybookStepResult.failed(detail) : PlaybookStepResult.done(detail, Map.of("amount", amount.amount(), "refundIds", legs.stream().map(Refund::id).toList()));
        } catch (GuardrailViolationException | ConflictException e) {
            return PlaybookStepResult.failed("refund refused: " + e.getMessage());
        }
    }
}
