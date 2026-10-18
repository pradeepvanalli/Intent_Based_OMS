package ai.intentoms.learning.service;

import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-episode reward (requirements doc §7): R(e) = w_fill*FillRate + w_otd*OnTime - w_cost*(Cost/OrderValue)
 * - w_split*Splits - w_fraud*FraudLoss - w_cx*Contacts + w_inv*AgedStockRelief.
 *
 * <p>Every term is normalised to [0,1] before the weights apply, so a weight means the same thing for every tenant:
 * fill = units shipped / units ordered; otd = 1 when delivered on time and not lost; cost = fulfilment cost / order
 * value (capped at 1); split = extra packages, 0 for one package, 0.5 for two, 1 for three or more; fraud = confirmed
 * fraud loss / order value (1 when the order value is unknown); cx = contacts, a complaint counting 1 and a WISMO
 * 0.5, capped at 1; inv = 1 when the order sold aged stock, 0 when it left stock stranded.
 *
 * <p>A term with no evidence yet is reported in {@link #scoredTerms} as unscored and contributes 0, so a provisional
 * reward is never mistaken for a complete one; {@link RewardMaturityService} decides when a term's window has closed.
 */
public final class RewardCalculator {
    private RewardCalculator() {}

    public static final List<String> TERMS = List.of("fill", "otd", "cost", "split", "fraud", "cx", "inv");

    public static Map<String, Double> components(List<OutcomeRecord> outcomes) {
        double units = 0, shipped = 0, cost = 0, orderValue = 0, fraudLoss = 0, contacts = 0;
        int packages = 0;
        Double onTime = null, invRelief = null;
        for (OutcomeRecord o : outcomes) {
            switch (o.payload()) {
                case OutcomeData.DeliveryOutcome d -> onTime = d.onTime() && !d.lost() ? 1.0 : 0.0;
                case OutcomeData.FulfillmentOutcome f -> {
                    packages += Math.max(1, f.packageCount());
                    units += f.unitsOrdered();
                    shipped += f.unitsShipped();
                    cost += f.fulfillmentCost();
                    orderValue = Math.max(orderValue, f.orderValue());
                }
                case OutcomeData.ReturnOutcome r -> { if (r.fraudConfirmed()) fraudLoss += r.refundAmount(); }
                case OutcomeData.PaymentOutcome p -> { if (p.chargeback()) fraudLoss += p.amount(); }
                case OutcomeData.ContactOutcome ct -> contacts += ct.complaint() ? 1.0 : 0.5;
                case OutcomeData.InventoryOutcome inv -> { if (inv.stranded()) invRelief = 0.0; else if (inv.daysToSale() > 0) invRelief = 1.0; } // a backorder resolution is not aged-stock relief
            }
        }
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("fill", units > 0 ? clamp(shipped / units) : 0.0);
        c.put("otd", onTime == null ? 0.0 : onTime);
        c.put("cost", orderValue > 0 ? clamp(cost / orderValue) : 0.0);
        c.put("split", packages <= 1 ? 0.0 : clamp((packages - 1) / 2.0));
        c.put("fraud", fraudLoss <= 0 ? 0.0 : orderValue > 0 ? clamp(fraudLoss / orderValue) : 1.0);
        c.put("cx", clamp(contacts));
        c.put("inv", invRelief == null ? 0.0 : invRelief);
        return c;
    }

    /** Terms that have evidence in these outcomes. A term outside this set is still open until its window closes. */
    public static Set<String> scoredTerms(List<OutcomeRecord> outcomes) {
        Set<String> s = new java.util.LinkedHashSet<>();
        for (OutcomeRecord o : outcomes) {
            switch (o.payload()) {
                case OutcomeData.DeliveryOutcome d -> s.add("otd");
                case OutcomeData.FulfillmentOutcome f -> {
                    s.add("split");
                    if (f.unitsOrdered() > 0) s.add("fill");
                    if (f.orderValue() > 0) s.add("cost");
                }
                case OutcomeData.ReturnOutcome r -> { if (r.fraudConfirmed()) s.add("fraud"); }
                case OutcomeData.PaymentOutcome p -> { if (p.chargeback()) s.add("fraud"); }
                case OutcomeData.ContactOutcome ct -> s.add("cx");
                case OutcomeData.InventoryOutcome inv -> { if (inv.stranded() || inv.daysToSale() > 0) s.add("inv"); }
            }
        }
        return s;
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

    public static double weight(ObjectiveWeights w, String term) {
        return switch (term) {
            case "fill" -> w.fill();
            case "otd" -> w.otd();
            case "cost" -> w.cost();
            case "split" -> w.split();
            case "fraud" -> w.fraud();
            case "cx" -> w.cx();
            case "inv" -> w.inv();
            default -> 0.0;
        };
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
