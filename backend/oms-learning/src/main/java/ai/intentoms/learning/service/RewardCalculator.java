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
 *       ships in two parcels does not double its denominator) and, for an episode about the whole order, are every unit
 *       ordered, so backordered, unsourced and cancelled units count against it. {@code fill_first_attempt} (not
 *       weighted) counts only the units shipped from the order's first sourcing;</li>
 *   <li>otd = share of delivered fulfilment groups that arrived on time and not lost;</li>
 *   <li>cost = fulfilment cost / order value, cost counted once per group, capped at 1;</li>
 *   <li>split = extra parcels: 0 for one parcel, 0.5 for two, 1 for three or more. Every parcel beyond the first counts;
 *       whether a single shipment was feasible is not known at outcome time, so this over-penalises an order no single
 *       node could have shipped (the sourcing decision's own split check carries that context);</li>
 *   <li>fraud = loss / order value (1 when the order value is unknown): confirmed-fraud returns (one loss per return,
 *       keyed by RMA), chargebacks (a won chargeback cancels its loss) and refused cash-on-delivery parcels;</li>
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
    /** Unweighted component: fill counting only the units the order's first sourcing shipped. */
    public static final String FILL_FIRST_ATTEMPT = "fill_first_attempt";
    /**
     * Unweighted §12 north-star component of an episode about the whole order: 1 for a perfect order, 0 otherwise,
     * absent while the order is still on its way. Perfect: delivered complete on the first attempt, on time and undamaged, with no customer contact, no
     * defect, not-as-described, wrong-item or not-received return, no chargeback and no refused delivery. Present once
     * the order is delivered, or as 0 once its delivery definitively failed (a lost parcel, a refused delivery).
     * Later outcomes (a contact, a return, a chargeback) correct it to 0 while the episode matures.
     */
    public static final String PERFECT = "perfect";
    static final Set<String> PERFECT_RETURN_FAILURES = Set.of("defective", "not_as_described", "wrong_item", "not_received", "damaged");

    /**
     * What the reward needs to know about the episode itself. {@code orderValue}: the order's value to fall back on when
     * no outcome reports one (an episode opened after shipment never sees {@code order.shipped}, and a loss there must
     * still be a share of the order, audit A4). {@code wholeOrder}: the episode is about the whole order (no group, no
     * line), so its fill counts every unit ordered, backordered and cancelled ones included.
     */
    public record Scope(double orderValue, boolean wholeOrder) {
        public static final Scope NONE = new Scope(0.0, false);
    }

    public static Map<String, Double> components(List<OutcomeRecord> outcomes) { return components(outcomes, Scope.NONE); }

    public static Map<String, Double> components(List<OutcomeRecord> outcomes, double fallbackOrderValue) { return components(outcomes, new Scope(fallbackOrderValue, false)); }

    public static Map<String, Double> components(List<OutcomeRecord> outcomes, Scope scope) {
        double shipped = 0, shippedFirstAttempt = 0, orderValue = 0, fraudLoss = 0, contacts = 0;
        int orderUnits = 0;
        Map<String, OutcomeData.ReturnOutcome> returns = new LinkedHashMap<>();
        Map<String, Integer> unitsByGroup = new LinkedHashMap<>();
        Map<String, Double> costByGroup = new LinkedHashMap<>();
        Set<String> parcels = new java.util.HashSet<>();
        int legacyPackages = 0, deliveries = 0, onTimeDeliveries = 0, row = 0;
        Double invRelief = null;
        // §12 perfect order: the order arrived (or definitively did not), and nothing about it went wrong.
        boolean delivered = false, failed = false, flawed = false;
        for (OutcomeRecord o : outcomes) {
            row++;
            switch (o.payload()) {
                case OutcomeData.DeliveryOutcome d -> {
                    deliveries++;
                    if (d.onTime() && !d.lost()) onTimeDeliveries++;
                    if (d.lost()) failed = true; else if (d.orderDelivered()) delivered = true;
                    if (!d.onTime() || d.lost() || d.damaged()) flawed = true;
                }
                case OutcomeData.FulfillmentOutcome f -> {
                    String group = f.fulfilmentGroupId() != null ? f.fulfilmentGroupId() : f.shipmentId() != null ? "order" : "row" + row;
                    unitsByGroup.merge(group, f.unitsOrdered(), Math::max);
                    costByGroup.merge(group, f.fulfillmentCost(), Math::max);
                    if (f.shipmentId() != null) parcels.add(f.shipmentId()); else legacyPackages += Math.max(1, f.packageCount());
                    shipped += f.unitsShipped();
                    if (f.fromFirstAttempt()) shippedFirstAttempt += f.unitsShipped();
                    orderUnits = Math.max(orderUnits, f.orderUnits());
                    orderValue = Math.max(orderValue, f.orderValue());
                }
                // One loss per return however many of its events were recorded (older rows were stored per event).
                case OutcomeData.ReturnOutcome r -> {
                    returns.merge(r.rmaId() != null ? r.rmaId() : "row" + row, r, OutcomeData.ReturnOutcome::merge);
                    if (r.reasonCode() != null && PERFECT_RETURN_FAILURES.contains(r.reasonCode())) flawed = true;
                }
                case OutcomeData.PaymentOutcome p -> {
                    if (p.chargeback() || p.refused()) fraudLoss += p.amount();
                    if (p.chargeback()) flawed = true;
                    if (p.refused()) failed = true;
                }
                case OutcomeData.ContactOutcome ct -> { contacts += ct.complaint() ? 1.0 : 0.5; flawed = true; }
                case OutcomeData.InventoryOutcome inv -> { if (inv.stranded()) invRelief = 0.0; else if (inv.daysToSale() > 0) invRelief = 1.0; } // a backorder resolution is not aged-stock relief
            }
        }
        for (OutcomeData.ReturnOutcome r : returns.values()) if (r.fraudConfirmed()) fraudLoss += r.refundAmount();
        if (orderValue <= 0) orderValue = Math.max(0.0, scope.orderValue());
        double units = unitsByGroup.values().stream().mapToInt(Integer::intValue).sum();
        if (scope.wholeOrder()) units = Math.max(units, orderUnits);
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
        // §7 "first attempt": the units the first sourcing shipped, over the same denominator. Not weighted.
        if (units > 0) c.put(FILL_FIRST_ATTEMPT, clamp(shippedFirstAttempt / units));
        if (scope.wholeOrder() && failed) c.put(PERFECT, 0.0);
        else if (scope.wholeOrder() && delivered && units > 0) c.put(PERFECT, !flawed && shippedFirstAttempt >= units ? 1.0 : 0.0);
        return c;
    }

    /** A loss is recorded but no outcome says what the order was worth: the caller should look the order value up. */
    public static boolean needsOrderValue(List<OutcomeRecord> outcomes) {
        boolean loss = false;
        for (OutcomeRecord o : outcomes) {
            switch (o.payload()) {
                case OutcomeData.FulfillmentOutcome f -> { if (f.orderValue() > 0) return false; }
                case OutcomeData.ReturnOutcome r -> loss |= r.fraudConfirmed();
                case OutcomeData.PaymentOutcome p -> loss |= p.chargeback() || p.refused();
                default -> { }
            }
        }
        return loss;
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
