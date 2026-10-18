package ai.intentoms.learning.service;

import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-episode reward (requirements doc §7): R(e) = w_fill*FillRate + w_otd*OnTime - w_cost*(Cost/OrderValue)
 * - w_split*Splits - w_fraud*FraudLoss - w_cx*Contacts + w_inv*AgedStockRelief.
 *
 * <p><b>Scope of this implementation:</b> oms-learning deliberately has no dependency on oms-finance/oms-order (it
 * only reads the event ledger and decision fabric), so the terms computable purely from the typed outcomes already
 * attached to an episode are implemented here in full: on-time, splits-avoided, fraud loss, customer contacts.
 * Fill-rate, cost-to-serve and aged-stock relief need order/finance figures this module doesn't have loaded; they
 * default to neutral (0) until the objective-function/experiment-manager build wires real inputs, and that is
 * exactly the extension point — {@link #compute} is pure and unit-tested so adding those terms is additive, not a
 * rewrite. Every component is reported separately in {@code rewardComponents} so a partial reward is never silently
 * mistaken for a complete one.
 */
public final class RewardCalculator {
    private RewardCalculator() {}

    public static Map<String, Double> components(List<OutcomeRecord> outcomes) {
        Map<String, Double> c = new LinkedHashMap<>();
        double onTime = 1.0, fraudLoss = 0.0, splits = 0.0, contacts = 0.0, invRelief = 0.0;
        boolean hasDelivery = false, hasInventory = false;
        for (OutcomeRecord o : outcomes) {
            switch (o.payload()) {
                case OutcomeData.DeliveryOutcome d -> { hasDelivery = true; onTime = d.onTime() && !d.lost() ? 1.0 : 0.0; }
                case OutcomeData.FulfillmentOutcome f -> { if (f.packageCount() > 1) splits = 1.0; }
                case OutcomeData.ReturnOutcome r -> { if (r.fraudConfirmed()) fraudLoss += r.refundAmount(); }
                case OutcomeData.PaymentOutcome p -> { if (p.chargeback()) fraudLoss += p.amount(); }
                case OutcomeData.ContactOutcome ct -> { if (ct.complaint()) contacts += 1.0; }
                // IU-03: 1.0 when this order sold through stock that was aging at its node (relief), 0 when the
                // allocation left stock stranded. Nothing produces an InventoryOutcome yet (OutcomeObserver has no
                // ledger event mapped to it) — this case is ready the moment one does, not a live signal today.
                case OutcomeData.InventoryOutcome inv -> { hasInventory = true; invRelief = inv.stranded() ? 0.0 : 1.0; }
                default -> {}
            }
        }
        c.put("fill", 0.0);               // needs order fill-rate; see class javadoc
        c.put("otd", hasDelivery ? onTime : 0.0);
        c.put("cost", 0.0);               // needs cost/order-value; see class javadoc
        c.put("split", splits);
        c.put("fraud", fraudLoss);
        c.put("cx", contacts);
        c.put("inv", hasInventory ? invRelief : 0.0);
        return c;
    }

    public static double compute(Map<String, Double> components, ObjectiveWeights w) {
        return w.fill() * components.getOrDefault("fill", 0.0)
            + w.otd() * components.getOrDefault("otd", 0.0)
            - w.cost() * components.getOrDefault("cost", 0.0)
            - w.split() * components.getOrDefault("split", 0.0)
            - w.fraud() * components.getOrDefault("fraud", 0.0)
            - w.cx() * components.getOrDefault("cx", 0.0)
            + w.inv() * components.getOrDefault("inv", 0.0);
    }
}
