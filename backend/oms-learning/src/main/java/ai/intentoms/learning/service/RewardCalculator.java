package ai.intentoms.learning.service;

import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The per-episode reward (requirements doc §7): R(e) = w_fill*FillRate + w_otd*OnTime - w_cost*(Cost/OrderValue)
 * - w_split*Splits - w_fraud*FraudLoss - w_cx*Contacts + w_inv*AgedStockRelief.
 *
 * <p>{@link #components} measures each term; {@link #normalise} then scales every term to [0,1] against the tenant's
 * baseline ({@link RewardBaselineService}), so a weight means the same thing for every tenant:
 * <ul>
 *   <li>fill = §2 FillRate: units shipped <em>on the first attempt</em> (from the order's first sourcing, not from a
 *       re-sourcing after a reject, shortage or backorder) / units ordered. Units ordered are counted once per
 *       fulfilment group (a group that ships in two parcels does not double its denominator) and, for an episode about
 *       the whole order, are every unit ordered, so backordered, unsourced and cancelled units count against it.
 *       {@code fill_all_attempts} (not weighted) counts the units shipped by any attempt;</li>
 *   <li>otd = share of delivered fulfilment groups that arrived on time and not lost;</li>
 *   <li>cost = fulfilment cost / order value, cost counted once per group, capped at 1;</li>
 *   <li>split = extra parcels: 0 for one parcel, 0.5 for two, 1 for three or more. Every parcel beyond the first counts;
 *       whether a single shipment was feasible is not known at outcome time, so this over-penalises an order no single
 *       node could have shipped (the sourcing decision's own split check carries that context);</li>
 *   <li>fraud = loss / order value (1 when the order value is unknown): confirmed-fraud returns (one loss per return,
 *       keyed by RMA), chargebacks (a won chargeback cancels its loss) and refused cash-on-delivery parcels. A refused
 *       parcel comes back, so its loss is the return-to-origin cost (the carrier's figure, else the outbound fulfilment
 *       cost again, else {@value #RTO_FALLBACK_SHARE} of the order value), not the order value, unless the refusal was
 *       confirmed as fraud;</li>
 *   <li>cx = contacts, a complaint counting 1 and a WISMO 0.5, capped at 1;</li>
 *   <li>inv = aged-stock relief: the age in days of the aged stock the order sold (scaled against the tenant's baseline
 *       age, so selling older stock relieves more), 0 when it left stock stranded.</li>
 * </ul>
 *
 * <p>A term with no evidence yet is reported in {@link #scoredTerms} as unscored and contributes 0, so a provisional
 * reward is never mistaken for a complete one; {@link RewardMaturityService} decides when a term's window has closed.
 */
public final class RewardCalculator {
    private RewardCalculator() {}

    public static final List<String> TERMS = List.of("fill", "otd", "cost", "split", "fraud", "cx", "inv");
    /** Unweighted component: fill counting the units shipped by any sourcing attempt (re-sourcings included). */
    public static final String FILL_ALL_ATTEMPTS = "fill_all_attempts";
    /**
     * Unweighted §12 north-star component of an episode about the whole order: 1 when every line of the order is perfect
     * (see {@link #PERFECT_LINES}), 0 otherwise, absent while the order is still on its way. Present once the order is
     * delivered, or as soon as its delivery definitively failed (a lost parcel, a refused delivery); later outcomes (a
     * return for a fulfilment reason) correct it while the episode matures.
     */
    public static final String PERFECT = "perfect";
    /**
     * Unweighted §2 north-star component: the share of the order's lines that are perfect. A line is perfect when it was
     * delivered complete on the first attempt, on time and undamaged, and was not returned for a fulfilment reason (see
     * {@link #fulfilmentFailure}). Customer contacts and chargebacks do not count: they are the cx and fraud terms.
     */
    public static final String PERFECT_LINES = "perfect_lines";
    /** Unweighted: how many order lines {@link #PERFECT_LINES} was judged on (1 for older rows without line identity). */
    public static final String LINES = "lines";

    /** Return reasons that blame fulfilment: the wrong item, damage or missing parts, late or never arrived. */
    static final Set<String> FULFILMENT_REASONS = Set.of("wrong_item", "damaged", "damaged_in_transit", "missing_parts", "arrived_late", "late", "not_received");
    /** Inspection gradings that show the parcel arrived damaged or incomplete. */
    static final Set<String> FULFILMENT_CONDITIONS = Set.of("damaged", "missing_parts");
    /** Reasons that are the customer's own choice: whatever the grading says, fulfilment did not cause the return. */
    static final Set<String> CUSTOMER_REASONS = Set.of("changed_mind", "change_of_mind", "wrong_size", "no_longer_needed", "found_cheaper", "ordered_by_mistake");
    /** Share of the order value a refused parcel costs when neither the carrier nor the shipment reported a cost. */
    static final double RTO_FALLBACK_SHARE = 0.1;

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
        double shipped = 0, shippedFirstAttempt = 0, orderValue = 0, fraudLoss = 0, contacts = 0, reportedReturnCost = 0;
        int orderUnits = 0;
        Map<String, OutcomeData.ReturnOutcome> returns = new LinkedHashMap<>();
        Map<String, Integer> unitsByGroup = new LinkedHashMap<>();
        Map<String, Double> costByGroup = new LinkedHashMap<>();
        Set<String> parcels = new HashSet<>();
        int legacyPackages = 0, deliveries = 0, onTimeDeliveries = 0, row = 0;
        Double invRelief = null;
        boolean refusedDelivery = false;
        Lines lines = new Lines();
        for (OutcomeRecord o : outcomes) {
            row++;
            switch (o.payload()) {
                case OutcomeData.DeliveryOutcome d -> {
                    deliveries++;
                    if (d.onTime() && !d.lost()) onTimeDeliveries++;
                    lines.deliveries.add(d);
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
                    lines.parcel(f, row);
                }
                // One loss per return however many of its events were recorded (older rows were stored per event).
                case OutcomeData.ReturnOutcome r -> returns.merge(r.rmaId() != null ? r.rmaId() : "row" + row, r, OutcomeData.ReturnOutcome::merge);
                case OutcomeData.PaymentOutcome p -> {
                    if (p.chargeback()) fraudLoss += p.amount();
                    if (p.refused()) {
                        refusedDelivery = true;
                        if (p.confirmedFraud()) fraudLoss += p.amount();
                        else if (p.returnCost() != null && p.returnCost() > 0) reportedReturnCost += p.returnCost();
                    }
                }
                case OutcomeData.ContactOutcome ct -> contacts += ct.complaint() ? 1.0 : 0.5;
                // Aged-stock relief: the age of the aged stock sold (a backorder resolution is not aged-stock relief).
                case OutcomeData.InventoryOutcome inv -> { if (inv.stranded()) invRelief = 0.0; else if (inv.daysToSale() > 0) invRelief = (double) inv.daysToSale(); }
            }
        }
        for (OutcomeData.ReturnOutcome r : returns.values()) if (r.fraudConfirmed()) fraudLoss += r.refundAmount();
        if (orderValue <= 0) orderValue = Math.max(0.0, scope.orderValue());
        double units = unitsByGroup.values().stream().mapToInt(Integer::intValue).sum();
        if (scope.wholeOrder()) units = Math.max(units, orderUnits);
        double cost = costByGroup.values().stream().mapToDouble(Double::doubleValue).sum();
        // A refused cash-on-delivery parcel travels back the way it came: the return leg costs what the outbound leg did
        // (which the cost term already counts), unless the carrier reported the figure. Confirmed fraud is a full loss above.
        boolean rtoUnconfirmed = outcomes.stream().anyMatch(o -> o.payload() instanceof OutcomeData.PaymentOutcome p && p.refused() && !p.confirmedFraud());
        double rtoCost = !rtoUnconfirmed ? 0 : reportedReturnCost > 0 ? reportedReturnCost : cost > 0 ? cost : RTO_FALLBACK_SHARE * orderValue;
        int packages = parcels.size() + legacyPackages;
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("fill", units > 0 ? clamp(shippedFirstAttempt / units) : 0.0);
        c.put("otd", deliveries == 0 ? 0.0 : (double) onTimeDeliveries / deliveries);
        c.put("cost", orderValue > 0 ? clamp(cost / orderValue) : 0.0);
        c.put("split", packages <= 1 ? 0.0 : clamp((packages - 1) / 2.0));
        double loss = fraudLoss + rtoCost;
        // With no order value a loss counts as the whole order, and an unpriced refusal as the fallback share of it.
        c.put("fraud", orderValue > 0 ? (loss <= 0 ? 0.0 : clamp(loss / orderValue)) : fraudLoss > 0 ? 1.0 : rtoUnconfirmed ? RTO_FALLBACK_SHARE : 0.0);
        c.put("cx", clamp(contacts));
        c.put("inv", invRelief == null ? 0.0 : invRelief);
        // §7 / §2: the weighted fill is the first attempt; every attempt's units, over the same denominator, are kept beside it.
        if (units > 0) c.put(FILL_ALL_ATTEMPTS, clamp(shipped / units));
        if (scope.wholeOrder()) lines.northStar(units, shippedFirstAttempt, returns.values(), refusedDelivery, c);
        return c;
    }

    /**
     * The §2 perfect-order rate, judged per line. Lines and their ordered units come from the parcels' {@code orderLines};
     * a line's parcels from their {@code lineUnits}; a delivery covers the lines of the parcels it names (or the lines it
     * names, or every line on older rows). Older rows with no line identity are judged as one line, the whole order.
     */
    private static final class Lines {
        final Map<String, Integer> ordered = new LinkedHashMap<>();
        final Map<String, Map<String, Integer>> firstAttemptUnitsByParcel = new LinkedHashMap<>();
        final Map<String, Set<String>> parcelsByLine = new LinkedHashMap<>();
        final List<OutcomeData.DeliveryOutcome> deliveries = new java.util.ArrayList<>();

        void parcel(OutcomeData.FulfillmentOutcome f, int row) {
            if (f.orderLines() != null) f.orderLines().forEach((line, qty) -> ordered.merge(line, qty == null ? 0 : qty, Math::max));
            if (f.lineUnits() == null) return;
            String parcel = f.shipmentId() != null ? f.shipmentId() : "row" + row;
            f.lineUnits().forEach((line, qty) -> {
                parcelsByLine.computeIfAbsent(line, k -> new LinkedHashSet<>()).add(parcel);
                // A redelivered parcel event names the same units again: counted once per parcel.
                if (f.fromFirstAttempt()) firstAttemptUnitsByParcel.computeIfAbsent(parcel, k -> new LinkedHashMap<>()).merge(line, qty == null ? 0 : qty, Math::max);
            });
        }

        void northStar(double units, double shippedFirstAttempt, java.util.Collection<OutcomeData.ReturnOutcome> returns, boolean refused, Map<String, Double> c) {
            boolean lost = deliveries.stream().anyMatch(OutcomeData.DeliveryOutcome::lost);
            boolean delivered = deliveries.stream().anyMatch(OutcomeData.DeliveryOutcome::orderDelivered);
            boolean byLine = !ordered.isEmpty();
            if (!byLine && units <= 0 && !refused && !lost) return;
            if (!(delivered || lost || refused)) return; // still on its way: no verdict yet
            Map<String, Integer> lineOrdered = byLine ? ordered : Map.of("*", (int) Math.round(units));
            int perfect = 0;
            for (Map.Entry<String, Integer> line : lineOrdered.entrySet()) {
                if (!refused && perfectLine(line.getKey(), line.getValue(), byLine, shippedFirstAttempt, returns)) perfect++;
            }
            int n = lineOrdered.size();
            c.put(PERFECT_LINES, n == 0 ? 0.0 : (double) perfect / n);
            c.put(LINES, (double) n);
            c.put(PERFECT, n > 0 && perfect == n ? 1.0 : 0.0);
        }

        private boolean perfectLine(String line, int orderedUnits, boolean byLine, double shippedFirstAttempt, java.util.Collection<OutcomeData.ReturnOutcome> returns) {
            double first = byLine ? firstAttemptUnitsByParcel.values().stream().mapToInt(m -> m.getOrDefault(line, 0)).sum() : shippedFirstAttempt;
            if (orderedUnits <= 0 || first < orderedUnits) return false; // not complete on the first attempt
            Set<String> myParcels = byLine ? parcelsByLine.getOrDefault(line, Set.of()) : Set.of();
            Set<String> arrived = new HashSet<>();
            boolean covered = false;
            for (OutcomeData.DeliveryOutcome d : deliveries) {
                if (!covers(d, line, myParcels, byLine)) continue;
                covered = true;
                if (!d.clean()) return false; // late, lost or damaged
                if (d.shipmentIds() != null) arrived.addAll(d.shipmentIds());
            }
            if (!covered) return false; // not delivered (yet)
            boolean parcelsKnown = deliveries.stream().anyMatch(d -> d.shipmentIds() != null && !d.shipmentIds().isEmpty());
            if (parcelsKnown && !myParcels.isEmpty() && !arrived.containsAll(myParcels)) return false; // part of the line still on its way
            for (OutcomeData.ReturnOutcome r : returns) if (returnedForFulfilment(r, line, byLine)) return false;
            return true;
        }

        private static boolean covers(OutcomeData.DeliveryOutcome d, String line, Set<String> myParcels, boolean byLine) {
            if (!byLine) return true;
            if (d.shipmentIds() != null && !d.shipmentIds().isEmpty() && !myParcels.isEmpty()) return d.shipmentIds().stream().anyMatch(myParcels::contains);
            if (d.lineIds() != null && !d.lineIds().isEmpty()) return d.lineIds().contains(line);
            return true;
        }

        private static boolean returnedForFulfilment(OutcomeData.ReturnOutcome r, String line, boolean byLine) {
            if (r.fraudConfirmed()) return false; // graded as abuse: the customer's claim was false, so fulfilment is not to blame
            if (r.lines() == null || r.lines().isEmpty() || !byLine) return fulfilmentFailure(r.reasonCode(), r.condition());
            for (OutcomeData.ReturnedLine l : r.lines()) {
                if (line.equals(l.lineId()) && fulfilmentFailure(l.reasonCode() != null ? l.reasonCode() : r.reasonCode(), l.condition() != null ? l.condition() : r.condition())) return true;
            }
            return false;
        }
    }

    /**
     * §2 "returned for fulfilment reasons": the customer's reason blames fulfilment (wrong item, damaged, missing parts,
     * arrived late, never arrived), or inspection found the parcel damaged or incomplete while the reason is not the
     * customer's own choice (a change of mind or the wrong size is never a fulfilment failure, whatever the grading).
     */
    static boolean fulfilmentFailure(String reasonCode, String condition) {
        String reason = reasonCode == null ? null : reasonCode.trim().toLowerCase(Locale.ROOT);
        String graded = condition == null ? null : condition.trim().toLowerCase(Locale.ROOT);
        if (reason != null && FULFILMENT_REASONS.contains(reason)) return true;
        return graded != null && FULFILMENT_CONDITIONS.contains(graded) && (reason == null || !CUSTOMER_REASONS.contains(reason));
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
        Set<String> s = new LinkedHashSet<>();
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
     * Documented default scales (§7 normalisation when the tenant has no baseline yet): each term's natural range, so a
     * scaled term equals the measured one. Cost / order value, splits (three or more parcels), fraud loss (the whole order
     * value) and contacts (one complaint) are already capped at 1; aged-stock relief is measured in days and defaults to
     * {@value #DEFAULT_AGED_DAYS} days, the age at which stock counts as aged, so any aged sale is full relief.
     */
    public static final double DEFAULT_AGED_DAYS = 30.0;
    public static final Map<String, Double> DEFAULT_SCALES = Map.of("fill", 1.0, "otd", 1.0, "cost", 1.0, "split", 1.0, "fraud", 1.0, "cx", 1.0, "inv", DEFAULT_AGED_DAYS);
    /** Terms that are already shares in [0,1] by definition: always scaled by the identity. */
    public static final Set<String> RATE_TERMS = Set.of("fill", "otd");

    /**
     * §7 normalisation against the tenant's baseline, never off: every term becomes {@code min(1, raw / scale)} with its
     * scale from {@code scales} or, when the baseline has none for it, {@link #DEFAULT_SCALES}. The measured value is kept
     * as {@code <term>_raw} whenever the scale is not 1. The rate terms (fill, on time) use the identity.
     */
    public static Map<String, Double> normalise(Map<String, Double> components, Map<String, Double> scales) {
        Map<String, Double> out = new LinkedHashMap<>(components);
        for (String term : TERMS) {
            Double raw = components.get(term);
            if (raw == null || RATE_TERMS.contains(term)) continue;
            Double s = scales == null ? null : scales.get(term);
            double scale = s != null && s > 0 ? s : DEFAULT_SCALES.get(term);
            if (Math.abs(scale - 1.0) < 1e-12) continue;
            out.put(term + "_raw", raw);
            out.put(term, clamp(raw / scale));
        }
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
