package ai.intentoms.learning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The typed outcome catalog (requirements doc OC-03). Each variant is a measured, typed result of one kind; an
 * episode accumulates these as they land (a delivery outcome first, a return outcome weeks later).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = OutcomeData.DeliveryOutcome.class, name = "DeliveryOutcome"),
    @JsonSubTypes.Type(value = OutcomeData.FulfillmentOutcome.class, name = "FulfillmentOutcome"),
    @JsonSubTypes.Type(value = OutcomeData.ReturnOutcome.class, name = "ReturnOutcome"),
    @JsonSubTypes.Type(value = OutcomeData.PaymentOutcome.class, name = "PaymentOutcome"),
    @JsonSubTypes.Type(value = OutcomeData.ContactOutcome.class, name = "ContactOutcome"),
    @JsonSubTypes.Type(value = OutcomeData.InventoryOutcome.class, name = "InventoryOutcome")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface OutcomeData {
    /**
     * Did the shipment arrive on time, and was it damaged (maturity window: 14 days, OC-04 default). {@code allDelivered}:
     * with this delivery every unit of the order has arrived (false for a parcel of a split order still waiting for
     * another, a lost parcel or a parcel returned to sender; null on older rows, which were whole-order deliveries).
     * {@code lineIds} / {@code shipmentIds}: the order lines and parcels this delivery (or carrier verdict) is about, so the
     * §2 perfect-order rate can judge each line by its own parcels; null on older rows, which then cover every line.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeliveryOutcome(boolean onTime, long lateByHours, boolean damaged, boolean lost, Boolean allDelivered,
                           java.util.List<String> lineIds, java.util.List<String> shipmentIds) implements OutcomeData {
        public DeliveryOutcome(boolean onTime, long lateByHours, boolean damaged, boolean lost) { this(onTime, lateByHours, damaged, lost, null); }

        public DeliveryOutcome(boolean onTime, long lateByHours, boolean damaged, boolean lost, Boolean allDelivered) {
            this(onTime, lateByHours, damaged, lost, allDelivered, null, null);
        }

        /** The order counts as delivered with this outcome. */
        public boolean orderDelivered() { return !lost && !Boolean.FALSE.equals(allDelivered); }

        /** On time, not lost and not damaged: nothing about this delivery spoils the lines it carried. */
        public boolean clean() { return onTime && !lost && !damaged; }
    }

    /**
     * One parcel leaving a node, and the figures the §7 fill, cost and split terms need. With {@code shipmentId} set
     * (current producers) the record describes that parcel: {@code unitsShipped} in it, while {@code unitsOrdered} and
     * {@code fulfillmentCost} belong to its fulfilment group and {@code orderValue} to the order, so the reward counts
     * them once per group / order however many parcels a group ships in, and counts parcels by distinct
     * {@code shipmentId} ({@code packageCount} is then the plan's package count, for information). Without
     * {@code shipmentId} (older rows) the record describes the whole order and {@code packageCount} is its package
     * count. A zero figure means the producer did not report it, and the matching reward term stays unscored.
     * {@code orderUnits} is every unit of the order (backordered and cancelled ones included: the fill denominator of an
     * order-level episode; 0 when not reported) and {@code firstAttempt} says the parcel's group came from the order's
     * first sourcing, not from a re-sourcing after a reject, shortage or backorder (the first-attempt fill).
     * {@code lineUnits} is the units of each order line in this parcel and {@code orderLines} every line of the order with
     * the units ordered (the §2 perfect-order rate is judged per line); both null on older rows.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FulfillmentOutcome(int packageCount, boolean splitFeasibleButAvoided, java.util.List<String> nodeIds, boolean storeRejected,
                              int unitsOrdered, int unitsShipped, double fulfillmentCost, double orderValue,
                              String fulfilmentGroupId, String shipmentId, int orderUnits, Boolean firstAttempt,
                              java.util.Map<String, Integer> lineUnits, java.util.Map<String, Integer> orderLines) implements OutcomeData {
        /** A parcel outcome without per-line units (rows written before the line-level perfect-order rate). */
        public FulfillmentOutcome(int packageCount, boolean splitFeasibleButAvoided, java.util.List<String> nodeIds, boolean storeRejected,
                                  int unitsOrdered, int unitsShipped, double fulfillmentCost, double orderValue,
                                  String fulfilmentGroupId, String shipmentId, int orderUnits, Boolean firstAttempt) {
            this(packageCount, splitFeasibleButAvoided, nodeIds, storeRejected, unitsOrdered, unitsShipped, fulfillmentCost, orderValue, fulfilmentGroupId, shipmentId,
                orderUnits, firstAttempt, null, null);
        }

        /** An order-level outcome (no parcel or group identity). */
        public FulfillmentOutcome(int packageCount, boolean splitFeasibleButAvoided, java.util.List<String> nodeIds, boolean storeRejected,
                                  int unitsOrdered, int unitsShipped, double fulfillmentCost, double orderValue) {
            this(packageCount, splitFeasibleButAvoided, nodeIds, storeRejected, unitsOrdered, unitsShipped, fulfillmentCost, orderValue, null, null);
        }

        /** A parcel outcome without the order's unit count or attempt (older rows). */
        public FulfillmentOutcome(int packageCount, boolean splitFeasibleButAvoided, java.util.List<String> nodeIds, boolean storeRejected,
                                  int unitsOrdered, int unitsShipped, double fulfillmentCost, double orderValue, String fulfilmentGroupId, String shipmentId) {
            this(packageCount, splitFeasibleButAvoided, nodeIds, storeRejected, unitsOrdered, unitsShipped, fulfillmentCost, orderValue, fulfilmentGroupId, shipmentId, 0, null);
        }

        /** From the first sourcing attempt; an older row that did not say counts as one. */
        public boolean fromFirstAttempt() { return firstAttempt == null || firstAttempt; }
    }

    /**
     * A return against this order/line (maturity window: 45 days). One per return: with {@code rmaId} set, the return's
     * disposition and refund events are merged into a single outcome ({@link #merge}) rather than each adding one, so a
     * loss is counted once. {@code refunded} says {@code refundAmount} is the refund actually issued rather than the
     * value of the returned lines while the refund is pending. {@code condition} is the inspection grading of the return
     * and {@code lines} the returned lines with their own reason and grading (null on older rows: the return then
     * covers every line of the order with the return's reason and condition).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReturnOutcome(String reasonCode, String disposition, boolean fraudConfirmed, double refundAmount, String rmaId, boolean refunded,
                         String condition, java.util.List<ReturnedLine> lines) implements OutcomeData {
        /** An outcome with no return identity (older rows, synthetic history). */
        public ReturnOutcome(String reasonCode, String disposition, boolean fraudConfirmed, double refundAmount) {
            this(reasonCode, disposition, fraudConfirmed, refundAmount, null, false);
        }

        /** A return without its inspection grading or lines (rows written before the line-level perfect-order rate). */
        public ReturnOutcome(String reasonCode, String disposition, boolean fraudConfirmed, double refundAmount, String rmaId, boolean refunded) {
            this(reasonCode, disposition, fraudConfirmed, refundAmount, rmaId, refunded, null, null);
        }

        /**
         * This return's outcome after a later event of the same return: grading that found fraud stays found, the
         * issued refund replaces the pending estimate (a refund is issued once per return), and the latest real
         * disposition wins (an automatic one followed by a person's choice is one outcome, not two). The latest known
         * grading and returned lines are kept.
         */
        public ReturnOutcome merge(ReturnOutcome later) {
            String disp = later.disposition() != null && !"refunded".equals(later.disposition()) ? later.disposition() : disposition != null ? disposition : later.disposition();
            double amount = later.refunded() ? later.refundAmount() : refunded ? refundAmount : Math.max(refundAmount, later.refundAmount());
            return new ReturnOutcome(later.reasonCode() != null ? later.reasonCode() : reasonCode, disp, fraudConfirmed || later.fraudConfirmed(), amount,
                rmaId != null ? rmaId : later.rmaId(), refunded || later.refunded(), later.condition() != null ? later.condition() : condition,
                later.lines() != null && !later.lines().isEmpty() ? later.lines() : lines);
        }
    }

    /** One returned line of a {@link ReturnOutcome}: the customer's reason and the inspection grading for that line. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReturnedLine(String lineId, String reasonCode, String condition) {}

    /**
     * Chargeback or payment-level result (maturity window: 120 days for chargebacks). {@code refused} is a payment that
     * never arrived (a cash-on-delivery parcel refused at the door, a failed card); {@code amount} is then the order's
     * value. A refused parcel comes back to the merchant, so unless it is {@code fraudConfirmed} its loss is the
     * return-to-origin cost ({@code returnCost} when the carrier reported one), not the order value. A chargeback the
     * merchant later won is recorded as {@code chargeback} with a negative {@code amount}, which cancels the earlier loss.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentOutcome(boolean chargeback, boolean refused, double amount, Boolean fraudConfirmed, Double returnCost) implements OutcomeData {
        /** A payment outcome without a fraud verdict or a reported return-to-origin cost. */
        public PaymentOutcome(boolean chargeback, boolean refused, double amount) { this(chargeback, refused, amount, null, null); }

        /** A refused delivery that was confirmed as fraud (not just a customer turning a parcel away). */
        public boolean confirmedFraud() { return Boolean.TRUE.equals(fraudConfirmed); }
    }

    /** A WISMO/complaint contact tied back to this order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContactOutcome(String channel, String reason, boolean complaint) implements OutcomeData {}

    /** Stock-level consequence: a stranded unit, a backorder resolved or cancelled. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InventoryOutcome(boolean stranded, boolean backorderCancelled, int daysToSale) implements OutcomeData {}
}
