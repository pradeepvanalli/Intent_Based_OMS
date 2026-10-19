package ai.intentoms.learning.service;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.platform.ledger.Event;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * OC-03 outcomes from payments, refused deliveries and customer contacts:
 * <ul>
 *   <li>{@code payment.chargeback}: a payment outcome with the disputed amount (the reward's fraud-loss term);
 *       {@code payment.chargeback_won} cancels it;</li>
 *   <li>{@code order.returned_to_sender}: for cash on delivery a refused payment (FD-02's outcome) whose loss is the
 *       return-to-origin cost, not the order value, unless the refusal is {@code fraudConfirmed}; otherwise a failed
 *       delivery (not on time) of the parcels' lines;</li>
 *   <li>{@code cs.complaint} / {@code cs.contact}: a contact, a complaint weighing more in the reward's contact term
 *       ({@code cs.wismo} is mapped by {@link CoreOutcomeMapper}).</li>
 * </ul>
 */
@Component
public class FraudContactOutcomeMapper implements OutcomeMapper {

    @Override
    public List<String> eventTypes() {
        return List.of("payment.chargeback", "payment.chargeback_won", "order.returned_to_sender", "cs.complaint", "cs.contact");
    }

    @Override
    public OutcomeData map(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        return switch (e.type()) {
            case "payment.chargeback" -> new OutcomeData.PaymentOutcome(true, false, num(p.get("amount")));
            // Won dispute: the money came back, so the earlier loss is cancelled (negative amount).
            case "payment.chargeback_won" -> new OutcomeData.PaymentOutcome(true, false, -Math.abs(num(p.get("amount"))));
            // A refused cash-on-delivery parcel comes back: its loss is the return-to-origin cost (the carrier's figure when
            // it reports one, else the reward estimates it), unless the refusal was confirmed as fraud.
            case "order.returned_to_sender" -> Boolean.TRUE.equals(p.get("cod"))
                ? new OutcomeData.PaymentOutcome(false, true, num(p.get("amount")), p.get("fraudConfirmed") instanceof Boolean b ? b : null,
                    p.get("returnCost") != null || p.get("rtoCost") != null ? num(p.getOrDefault("returnCost", p.get("rtoCost"))) : null)
                : new OutcomeData.DeliveryOutcome(false, 0, false, false, false, CoreOutcomeMapper.stringsOrNull(p.get("lineIds")), CoreOutcomeMapper.stringsOrNull(p.get("shipments")));
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
