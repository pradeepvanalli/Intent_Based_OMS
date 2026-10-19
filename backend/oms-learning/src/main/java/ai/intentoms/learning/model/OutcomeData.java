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
    /** Did the shipment arrive on time, and was it damaged (maturity window: 14 days, OC-04 default). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeliveryOutcome(boolean onTime, long lateByHours, boolean damaged, boolean lost) implements OutcomeData {}

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
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FulfillmentOutcome(int packageCount, boolean splitFeasibleButAvoided, java.util.List<String> nodeIds, boolean storeRejected,
                              int unitsOrdered, int unitsShipped, double fulfillmentCost, double orderValue,
                              String fulfilmentGroupId, String shipmentId, int orderUnits, Boolean firstAttempt) implements OutcomeData {
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
     * value of the returned lines while the refund is pending.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReturnOutcome(String reasonCode, String disposition, boolean fraudConfirmed, double refundAmount, String rmaId, boolean refunded) implements OutcomeData {
        /** An outcome with no return identity (older rows, synthetic history). */
        public ReturnOutcome(String reasonCode, String disposition, boolean fraudConfirmed, double refundAmount) {
            this(reasonCode, disposition, fraudConfirmed, refundAmount, null, false);
        }

        /**
         * This return's outcome after a later event of the same return: grading that found fraud stays found, the
         * issued refund replaces the pending estimate (a refund is issued once per return), and the latest real
         * disposition wins (an automatic one followed by a person's choice is one outcome, not two).
         */
        public ReturnOutcome merge(ReturnOutcome later) {
            String disp = later.disposition() != null && !"refunded".equals(later.disposition()) ? later.disposition() : disposition != null ? disposition : later.disposition();
            double amount = later.refunded() ? later.refundAmount() : refunded ? refundAmount : Math.max(refundAmount, later.refundAmount());
            return new ReturnOutcome(later.reasonCode() != null ? later.reasonCode() : reasonCode, disp, fraudConfirmed || later.fraudConfirmed(), amount,
                rmaId != null ? rmaId : later.rmaId(), refunded || later.refunded());
        }
    }

    /**
     * Chargeback or payment-level result (maturity window: 120 days for chargebacks). {@code refused} is a payment that
     * never arrived (a cash-on-delivery parcel refused at the door, a failed card). A chargeback the merchant later
     * won is recorded as {@code chargeback} with a negative {@code amount}, which cancels the earlier loss.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentOutcome(boolean chargeback, boolean refused, double amount) implements OutcomeData {}

    /** A WISMO/complaint contact tied back to this order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContactOutcome(String channel, String reason, boolean complaint) implements OutcomeData {}

    /** Stock-level consequence: a stranded unit, a backorder resolved or cancelled. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InventoryOutcome(boolean stranded, boolean backorderCancelled, int daysToSale) implements OutcomeData {}
}
