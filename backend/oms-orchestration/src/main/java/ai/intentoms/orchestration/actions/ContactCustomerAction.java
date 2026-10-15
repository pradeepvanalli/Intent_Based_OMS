package ai.intentoms.orchestration.actions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playbook {@code contact_customer} (FR-MON-04, GR-15): sends a templated message ({@code template}, {@code channel}
 * email|sms, optional {@code subject}) through the {@link CustomerNotifier}; only typed slots (exception type, order id,
 * node) are rendered — never model free text.
 */
@Component
public class ContactCustomerAction implements PlaybookAction {
    private final CustomerNotifier notifier;

    public ContactCustomerAction(CustomerNotifier notifier) { this.notifier = notifier; }

    @Override public String action() { return "contact_customer"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        String orderId = ctx.orderId();
        if (orderId == null) return PlaybookStepResult.skipped("no order / customer to contact");
        String template = ActionSupport.str(step, "template", "order_update");
        String channel = ActionSupport.str(step, "channel", "email");
        String subject = ActionSupport.str(step, "subject", subjectFor(template));
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("orderId", orderId);
        slots.put("exceptionType", ctx.exception().type());
        if (ctx.exception().nodeId() != null) slots.put("nodeId", ctx.exception().nodeId());
        String summary = subject + " (order " + orderId + ", " + ctx.exception().type().replace('_', ' ') + ")";
        String id = notifier.notify(orderId, channel, template, subject, summary, slots);
        return PlaybookStepResult.done("sent " + template + " via " + channel + " (" + id + ")", Map.of("communicationId", id, "template", template, "channel", channel));
    }

    static String subjectFor(String template) {
        return switch (template) {
            case "delay_notice" -> "Your delivery is taking longer than expected";
            case "address_confirm" -> "Please confirm your delivery address";
            case "return_reminder" -> "Reminder: send your return back";
            case "pickup_reminder" -> "Your order is waiting for pickup";
            case "ready_for_pickup" -> "Your order is ready for pickup";
            case "resourced" -> "Your order will ship from a different location";
            default -> "An update on your order";
        };
    }
}
