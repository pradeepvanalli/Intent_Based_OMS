package ai.intentoms.inventory.learning;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.service.OutcomeMapper;
import ai.intentoms.platform.ledger.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OC-03 inventory outcomes ({@code InventoryOutcome}), attached by {@code OutcomeObserver} to the episodes of the order
 * the event names:
 * <ul>
 *   <li>{@code inventory.stranded}: stock left to age because the named order was sourced elsewhere (stranded);</li>
 *   <li>{@code reservation.consumed} of stock at least {@code oms.inventory.learning.aged-days} old (default 30): the order
 *       used up aged stock, the "aged stock relief" reward term;</li>
 *   <li>an order line leaving {@code backordered}: the backorder was kept (sold later) or cancelled.</li>
 * </ul>
 */
@Component
public class InventoryOutcomeMapper implements OutcomeMapper {
    private final int agedDays;

    public InventoryOutcomeMapper(@Value("${oms.inventory.learning.aged-days:30}") int agedDays) { this.agedDays = agedDays; }

    @Override
    public List<String> eventTypes() { return List.of(StockAgeLearner.STRANDED_EVENT, "reservation.consumed", "order.transitioned"); }

    @Override
    public OutcomeData map(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        return switch (e.type()) {
            case StockAgeLearner.STRANDED_EVENT -> p.get("orderId") == null ? null : new OutcomeData.InventoryOutcome(true, false, intOf(p.get("daysAged")));
            case "reservation.consumed" -> {
                int age = intOf(p.get("stockAgeDays"));
                yield p.get("orderId") != null && p.get("stockAgeDays") != null && age >= agedDays ? new OutcomeData.InventoryOutcome(false, false, age) : null;
            }
            case "order.transitioned" -> {
                Optional<BackorderOutcomeLearner.Resolution> r = BackorderOutcomeLearner.resolutionOf(e);
                yield r.map(res -> (OutcomeData) new OutcomeData.InventoryOutcome(false, "cancelled".equals(res.outcome()), 0)).orElse(null);
            }
            default -> null;
        };
    }

    private static int intOf(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
