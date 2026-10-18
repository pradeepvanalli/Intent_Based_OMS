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

    /** How the fulfillment plan actually played out: split count, node(s) used, a store reject if any. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FulfillmentOutcome(int packageCount, boolean splitFeasibleButAvoided, java.util.List<String> nodeIds, boolean storeRejected) implements OutcomeData {}

    /** A return against this order/line (maturity window: 45 days). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReturnOutcome(String reasonCode, String disposition, boolean fraudConfirmed, double refundAmount) implements OutcomeData {}

    /** Chargeback or payment-level result (maturity window: 120 days for chargebacks). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentOutcome(boolean chargeback, boolean refused, double amount) implements OutcomeData {}

    /** A WISMO/complaint contact tied back to this order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContactOutcome(String channel, String reason, boolean complaint) implements OutcomeData {}

    /** Stock-level consequence: a stranded unit, a backorder resolved or cancelled. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InventoryOutcome(boolean stranded, boolean backorderCancelled, int daysToSale) implements OutcomeData {}
}
