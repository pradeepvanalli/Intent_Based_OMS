package ai.intentoms.learning.service;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.platform.ledger.Event;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outcomes from the order lifecycle events the core workflows already emit: delivery (on time, late by, damaged,
 * lost, including a carrier exception that reports the parcel lost or damaged, with the lines and parcels it covers),
 * shipment (packages, units, cost, order value, units per line), refunds, failed payments, return dispositions (with the
 * inspection grading and the returned lines) and WISMO contacts. Payload fields come from the producers ({@code StandardShipOrderWorkflow}, returns, finance, CS); a
 * missing field falls back to a neutral value rather than a guess that would bias the reward.
 */
@Component
public class CoreOutcomeMapper implements OutcomeMapper {

    @Override
    public List<String> eventTypes() {
        return List.of("order.delivered", "event.delivered", "order.carrier_exception", "order.shipped", "event.shipped",
            "return.refunded", "return.dispositioned", "refund.issued", "oms.returns.refunded", "oms.returns.disposition", "cs.wismo",
            "order.resourced");
    }

    @Override
    public OutcomeData map(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        return switch (e.type()) {
            case "order.delivered", "event.delivered" -> new OutcomeData.DeliveryOutcome(
                bool(p.get("onTime"), true), num(p.get("lateByHours")).longValue(), bool(p.get("damaged"), false), bool(p.get("lost"), false),
                p.get("allDelivered") instanceof Boolean all ? all : null, stringsOrNull(p.get("lineIds")), stringsOrNull(p.get("shipments")));
            // A parcel the carrier reports lost or damaged is that parcel's delivery outcome: a lost one never gets a
            // delivery scan (audit A6). Any other exception (a delay) is not an outcome; the delivery scan will be.
            // A4-4: lost is definitively not on time; damaged alone is not — on-time stays unresolved (true) unless
            // the producer (StandardShipOrderWorkflow.carrierFacts) says the touched groups' promise date has
            // already passed, so a damaged-but-not-yet-late parcel is not scored as late before any delivery scan.
            case "order.carrier_exception" -> bool(p.get("lost"), false) || bool(p.get("damaged"), false)
                ? new OutcomeData.DeliveryOutcome(!bool(p.get("lost"), false) && bool(p.get("onTime"), true), 0, bool(p.get("damaged"), false), bool(p.get("lost"), false), false,
                    stringsOrNull(p.get("lineIds")), stringsOrNull(p.get("shipments"))) : null;
            case "order.shipped", "event.shipped" -> new OutcomeData.FulfillmentOutcome(
                Math.max(1, num(p.get("packageCount")).intValue()), bool(p.get("splitAvoided"), false), strings(p.get("nodeIds")),
                bool(p.get("storeRejected"), false), num(p.get("unitsOrdered")).intValue(), num(p.get("unitsShipped")).intValue(),
                num(p.get("fulfillmentCost")).doubleValue(), num(p.get("orderValue")).doubleValue(),
                str(p.getOrDefault("fulfilmentGroupId", p.get("fulfillmentGroupId"))), str(p.get("shipmentId")),
                num(p.get("orderUnits")).intValue(), p.get("firstAttempt") instanceof Boolean b ? b : null,
                units(p.get("lines")), units(p.get("orderLines")));
            // A return is an outcome for the order it came back from (the event carries orderId): grading sets
            // fraudConfirmed (FD-06) and the refund is the loss when it was abuse.
            // One outcome per return: rmaId keys the merge of its disposition and refund events (OutcomeObserver).
            case "return.refunded", "return.dispositioned", "refund.issued", "oms.returns.refunded", "oms.returns.disposition" -> new OutcomeData.ReturnOutcome(
                str(p.get("reasonCode")), p.get("disposition") == null ? ("return.refunded".equals(e.type()) ? "refunded" : null) : str(p.get("disposition")),
                bool(p.get("fraudConfirmed"), false), num(p.getOrDefault("refundAmount", p.get("amount"))).doubleValue(),
                p.get("rmaId") != null ? str(p.get("rmaId")) : "return".equals(e.aggregateType()) ? e.aggregateId() : null,
                REFUND_EVENTS.contains(e.type()), str(p.get("condition")), returnedLines(p.get("returnLines")));
            case "cs.wismo" -> new OutcomeData.ContactOutcome(p.get("channel") == null ? "cs" : str(p.get("channel")), "wismo", false);
            // OC-02: a node that refused its group is the outcome of the choice that planned the group: nothing shipped from
            // it (first-attempt fill 0) and the store rejected. A shortage at reservation is not a refusal (nodeRejected
            // false): the replacement's own shipments score the re-sourcing decision, but the abandoned choice still needs
            // a definite, non-blaming end of its own (A4-2) rather than staying open forever with no outcome at all.
            case "order.resourced" -> refusal(p);
            default -> null;
        };
    }

    /**
     * A4-2: every rejected group gives the decision point that planned it a definite outcome, so the episode is never
     * left open with no outcome at all (it would otherwise mature with a null reward and count as uncovered, OC-07).
     * A genuine refusal ({@code nodeRejected true}) keeps blaming the node (fill 0, {@code storeRejected true}, the
     * units it failed to ship). A reservation-race shortage ({@code nodeRejected false}) is nobody's refusal: the
     * outcome is neutral — {@code storeRejected false} (A4-1 carries no node blame) and no units counted against fill
     * (the choice never got the chance to ship anything; the replacement's own outcomes score the re-sourcing decision).
     */
    static OutcomeData.FulfillmentOutcome refusal(Map<String, Object> p) {
        if (!(p.get("rejectedGroupIds") instanceof List<?> groups) || groups.isEmpty()) return null;
        boolean nodeRejected = bool(p.get("nodeRejected"), true);
        int units = nodeRejected ? Math.max(1, num(p.get("rejectedUnits")).intValue()) : 0;
        return new OutcomeData.FulfillmentOutcome(1, false, strings(p.get("rejectedNodes")), nodeRejected, units, 0, 0.0, 0.0, String.valueOf(groups.get(0)), null, 0, true);
    }

    /**
     * The terminal outcome of a rejected group ({@link #refusal}, A4-1 / A4-2): it belongs only to the decision point
     * that planned the group, whether the group was refused or just abandoned after a shortage.
     */
    static boolean isRefusal(OutcomeData data) {
        return data instanceof OutcomeData.FulfillmentOutcome f && f.unitsShipped() == 0 && f.shipmentId() == null && f.fulfilmentGroupId() != null;
    }

    private static final java.util.Set<String> REFUND_EVENTS = java.util.Set.of("return.refunded", "refund.issued", "oms.returns.refunded");

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static boolean bool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }
    static Number num(Object o) { return o instanceof Number n ? n : 0; }
    static List<String> strings(Object o) { return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(); }
    static List<String> stringsOrNull(Object o) { return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null; }

    /** {@code {lineId: units}} from an event, or null when the event does not say. */
    static Map<String, Integer> units(Object o) {
        if (!(o instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> { if (k != null && v instanceof Number n) out.put(String.valueOf(k), n.intValue()); });
        return out.isEmpty() ? null : out;
    }

    /** The returned lines of a return event ({@code [{lineId, reasonCode, condition}]}), or null when the event does not say. */
    static List<OutcomeData.ReturnedLine> returnedLines(Object o) {
        if (!(o instanceof List<?> l) || l.isEmpty()) return null;
        List<OutcomeData.ReturnedLine> out = new java.util.ArrayList<>();
        for (Object x : l) if (x instanceof Map<?, ?> m && m.get("lineId") != null) out.add(new OutcomeData.ReturnedLine(str(m.get("lineId")), str(m.get("reasonCode")), str(m.get("condition"))));
        return out.isEmpty() ? null : out;
    }
}
