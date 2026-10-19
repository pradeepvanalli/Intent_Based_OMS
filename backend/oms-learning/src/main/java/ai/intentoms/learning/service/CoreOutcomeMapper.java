package ai.intentoms.learning.service;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.platform.ledger.Event;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outcomes from the order lifecycle events the core workflows already emit: delivery (on time, late by, damaged,
 * lost), shipment (packages, units, cost, order value), refunds, failed payments, return dispositions and WISMO
 * contacts. Payload fields come from the producers ({@code StandardShipOrderWorkflow}, returns, finance, CS); a
 * missing field falls back to a neutral value rather than a guess that would bias the reward.
 */
@Component
public class CoreOutcomeMapper implements OutcomeMapper {

    @Override
    public List<String> eventTypes() {
        return List.of("order.delivered", "event.delivered", "order.shipped", "event.shipped",
            "return.refunded", "return.dispositioned", "refund.issued", "oms.returns.refunded", "oms.returns.disposition", "cs.wismo");
    }

    @Override
    public OutcomeData map(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        return switch (e.type()) {
            case "order.delivered", "event.delivered" -> new OutcomeData.DeliveryOutcome(
                bool(p.get("onTime"), true), num(p.get("lateByHours")).longValue(), bool(p.get("damaged"), false), bool(p.get("lost"), false));
            case "order.shipped", "event.shipped" -> new OutcomeData.FulfillmentOutcome(
                Math.max(1, num(p.get("packageCount")).intValue()), bool(p.get("splitAvoided"), false), strings(p.get("nodeIds")),
                bool(p.get("storeRejected"), false), num(p.get("unitsOrdered")).intValue(), num(p.get("unitsShipped")).intValue(),
                num(p.get("fulfillmentCost")).doubleValue(), num(p.get("orderValue")).doubleValue(),
                str(p.getOrDefault("fulfilmentGroupId", p.get("fulfillmentGroupId"))), str(p.get("shipmentId")));
            // A return is an outcome for the order it came back from (the event carries orderId): grading sets
            // fraudConfirmed (FD-06) and the refund is the loss when it was abuse.
            case "return.refunded", "return.dispositioned", "refund.issued", "oms.returns.refunded", "oms.returns.disposition" -> new OutcomeData.ReturnOutcome(
                str(p.get("reasonCode")), p.get("disposition") == null ? ("return.refunded".equals(e.type()) ? "refunded" : null) : str(p.get("disposition")),
                bool(p.get("fraudConfirmed"), false), num(p.getOrDefault("refundAmount", p.get("amount"))).doubleValue());
            case "cs.wismo" -> new OutcomeData.ContactOutcome(p.get("channel") == null ? "cs" : str(p.get("channel")), "wismo", false);
            default -> null;
        };
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static boolean bool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }
    static Number num(Object o) { return o instanceof Number n ? n : 0; }
    static List<String> strings(Object o) { return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(); }
}
