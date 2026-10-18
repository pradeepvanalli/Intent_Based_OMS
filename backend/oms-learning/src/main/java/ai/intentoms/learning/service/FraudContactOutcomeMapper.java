package ai.intentoms.learning.service;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.platform.ledger.Event;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * OC-03 outcomes from payments, refused deliveries and customer contacts:
 * <ul>
 *   <li>{@code payment.chargeback}: a payment outcome with the disputed amount (the reward's fraud-loss term);</li>
 *   <li>{@code order.returned_to_sender}: for cash on delivery a refused payment (FD-02's outcome), otherwise a failed
 *       delivery (not on time);</li>
 *   <li>{@code cs.complaint} / {@code cs.contact}: a contact, a complaint weighing more in the reward's contact term
 *       ({@code cs.wismo} is mapped by {@link CoreOutcomeMapper}).</li>
 * </ul>
 */
@Component
public class FraudContactOutcomeMapper implements OutcomeMapper {

    @Override
    public List<String> eventTypes() {
        return List.of("payment.chargeback", "order.returned_to_sender", "cs.complaint", "cs.contact");
    }

    @Override
    public OutcomeData map(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        return switch (e.type()) {
            case "payment.chargeback" -> new OutcomeData.PaymentOutcome(true, false, num(p.get("amount")));
            case "order.returned_to_sender" -> Boolean.TRUE.equals(p.get("cod"))
                ? new OutcomeData.PaymentOutcome(false, true, num(p.get("amount")))
                : new OutcomeData.DeliveryOutcome(false, 0, false, false);
            case "cs.complaint" -> new OutcomeData.ContactOutcome(str(p.get("channel"), "cs"), str(p.get("reason"), "complaint"), true);
            case "cs.contact" -> new OutcomeData.ContactOutcome(str(p.get("channel"), "cs"), str(p.get("reason"), "other"), false);
            default -> null;
        };
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    private static String str(Object o, String dflt) { return o == null ? dflt : String.valueOf(o); }
}
