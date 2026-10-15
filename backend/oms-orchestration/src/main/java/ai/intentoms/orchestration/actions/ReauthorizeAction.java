package ai.intentoms.orchestration.actions;

import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.finance.payment.PaymentStatus;
import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Playbook {@code reauthorize} (FR-MON-04, FR-FIN-01, UC-06 authorisation expiry): re-authorises every open payment of the
 * order expiring within {@code within} (ISO duration, default P3D). A gateway decline is a failed step so the
 * {@code payment_retry} playbook can place a payment hold and open a finance task.
 */
@Component
public class ReauthorizeAction implements PlaybookAction {
    private final PaymentService payments;

    public ReauthorizeAction(PaymentService payments) { this.payments = payments; }

    @Override public String action() { return "reauthorize"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        if (ctx.orderId() == null) return PlaybookStepResult.skipped("no order to re-authorise");
        Duration within;
        try { within = Duration.parse(ActionSupport.str(step, "within", "P3D")); } catch (DateTimeParseException e) { within = Duration.ofDays(3); }
        List<Payment> touched = payments.reauthorizeIfExpiring(ctx.orderId(), within);
        if (touched.isEmpty()) return PlaybookStepResult.skipped("no authorisation expiring within " + within);
        List<Payment> failed = touched.stream().filter(p -> p.status() == PaymentStatus.FAILED).toList();
        String detail = "re-authorised " + (touched.size() - failed.size()) + " of " + touched.size() + " payment(s)";
        Map<String, Object> data = Map.of("paymentIds", touched.stream().map(Payment::id).toList(), "failed", failed.stream().map(Payment::id).toList());
        return failed.isEmpty() ? PlaybookStepResult.done(detail, data) : PlaybookStepResult.failed(detail + "; declined: " + failed.stream().map(Payment::failureCode).toList());
    }
}
