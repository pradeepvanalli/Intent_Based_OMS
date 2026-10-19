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
 * <ul>
 *   <li>fill = units shipped / units ordered, where units ordered are counted once per fulfilment group (a group that
 *       ships in two parcels does not double its denominator);</li>
 *   <li>otd = share of delivered fulfilment groups that arrived on time and not lost;</li>
 *   <li>cost = fulfilment cost / order value, cost counted once per group, capped at 1;</li>
 *   <li>split = extra parcels: 0 for one parcel, 0.5 for two, 1 for three or more. Every parcel beyond the first counts;
 *       whether a single shipment was feasible is not known at outcome time, so this over-penalises an order no single
 *       node could have shipped (the sourcing decision's own split check carries that context);</li>
 *   <li>fraud = loss / order value (1 when the order value is unknown): confirmed-fraud returns, chargebacks (a won
 *       chargeback cancels its loss) and refused cash-on-delivery parcels;</li>
 *   <li>cx = contacts, a complaint counting 1 and a WISMO 0.5, capped at 1;</li>
 *   <li>inv = 1 when the order sold aged stock, 0 when it left stock stranded.</li>
 * </ul>
 *
 * <p>A term with no evidence yet is reported in {@link #scoredTerms} as unscored and contributes 0, so a provisional
 * reward is never mistaken for a complete one; {@link RewardMaturityService} decides when a term's window has closed.
 */
public final class RewardCalculator {
    private RewardCalculator() {}

    public static final List<String> TERMS = List.of("fill", "otd", "cost", "split", "fraud", "cx", "inv");

    public static Map<String, Double> components(List<OutcomeRecord> outcomes) {
        double shipped = 0, orderValue = 0, fraudLoss = 0, contacts = 0;
        Map<String, Integer> unitsByGroup = new LinkedHashMap<>();
        Map<String, Double> costByGroup = new LinkedHashMap<>();
        Set<String> parcels = new java.util.HashSet<>();
        int legacyPackages = 0, deliveries = 0, onTimeDeliveries = 0, row = 0;
        Double invRelief = null;
        for (OutcomeRecord o : outcomes) {
            row++;
            switch (o.payload()) {
                case OutcomeData.DeliveryOutcome d -> { deliveries++; if (d.onTime() && !d.lost()) onTimeDeliveries++; }
                case OutcomeData.FulfillmentOutcome f -> {
                    String group = f.fulfilmentGroupId() != null ? f.fulfilmentGroupId() : f.shipmentId() != null ? "order" : "row" + row;
                    unitsByGroup.merge(group, f.unitsOrdered(), Math::max);
                    costByGroup.merge(group, f.fulfillmentCost(), Math::max);
                    if (f.shipmentId() != null) parcels.add(f.shipmentId()); else legacyPackages += Math.max(1, f.packageCount());
                    shipped += f.unitsShipped();
                    orderValue = Math.max(orderValue, f.orderValue());
                }
                case OutcomeData.ReturnOutcome r -> { if (r.fraudConfirmed()) fraudLoss += r.refundAmount(); }
                case OutcomeData.PaymentOutcome p -> { if (p.chargeback() || p.refused()) fraudLoss += p.amount(); }
                case OutcomeData.ContactOutcome ct -> contacts += ct.complaint() ? 1.0 : 0.5;
                case OutcomeData.InventoryOutcome inv -> { if (inv.stranded()) invRelief = 0.0; else if (inv.daysToSale() > 0) invRelief = 1.0; } // a backorder resolution is not aged-stock relief
            }
        }
        double units = unitsByGroup.values().stream().mapToInt(Integer::intValue).sum();
        double cost = costByGroup.values().stream().mapToDouble(Double::doubleValue).sum();
        int packages = parcels.size() + legacyPackages;
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("fill", units > 0 ? clamp(shipped / units) : 0.0);
        c.put("otd", deliveries == 0 ? 0.0 : (double) onTimeDeliveries / deliveries);
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
                case OutcomeData.PaymentOutcome p -> { if (p.chargeback() || p.refused()) s.add("fraud"); }
                case OutcomeData.ContactOutcome ct -> s.add("cx");
                case OutcomeData.InventoryOutcome inv -> { if (inv.stranded() || inv.daysToSale() > 0) s.add("inv"); }
            }
        }
        return s;
    }

    /**
     * §7 normalisation against the tenant's baseline: each scaled penalty term becomes {@code min(1, raw / scale)},
     * with the raw value kept as {@code <term>_raw}. Terms without a scale are left as they are.
     */
    public static Map<String, Double> normalise(Map<String, Double> components, Map<String, Double> scales) {
        if (scales == null || scales.isEmpty()) return components;
        Map<String, Double> out = new LinkedHashMap<>(components);
        scales.forEach((term, scale) -> {
            Double raw = components.get(term);
            if (raw == null || scale == null || scale <= 0) return;
            out.put(term + "_raw", raw);
            out.put(term, Math.min(1.0, raw / scale));
        });
        return out;
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
