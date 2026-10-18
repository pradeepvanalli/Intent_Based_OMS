package ai.intentoms.learning.service;

import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OC-05: when an episode ends badly, which part of the chain most likely caused it: the stock position, the node that
 * fulfilled it, the carrier, or the customer. Rule-based and transparent on purpose, so a person can check it:
 * <ul>
 *   <li>lost parcel: carrier; damaged: carrier 0.7, node (packing) 0.3;</li>
 *   <li>late delivery: carrier 0.6, node 0.4; when a node rejected part of the order first, node 0.6, carrier 0.4;</li>
 *   <li>a store reject: node; a split into several packages: stock (no single node had it all);</li>
 *   <li>stranded stock or a cancelled backorder: stock;</li>
 *   <li>a chargeback, a refused cash-on-delivery parcel, or a confirmed-fraud return: customer.</li>
 * </ul>
 * Each bad signal contributes its split; the result is normalised to sum to 1 and returned as {@code attr.stock},
 * {@code attr.node}, {@code attr.carrier}, {@code attr.customer} entries stored next to the reward components. Empty
 * when nothing went wrong. Contacts follow the delivery they complain about, so they add no blame of their own.
 */
public final class OutcomeAttribution {
    private OutcomeAttribution() {}

    public static final List<String> PARTIES = List.of("stock", "node", "carrier", "customer");

    public static Map<String, Double> attribute(List<OutcomeRecord> outcomes) {
        double stock = 0, node = 0, carrier = 0, customer = 0;
        boolean rejected = outcomes.stream().anyMatch(o -> o.payload() instanceof OutcomeData.FulfillmentOutcome f && f.storeRejected());
        for (OutcomeRecord o : outcomes) {
            switch (o.payload()) {
                case OutcomeData.DeliveryOutcome d -> {
                    if (d.lost()) carrier += 1;
                    else if (d.damaged()) { carrier += 0.7; node += 0.3; }
                    else if (!d.onTime()) { if (rejected) { node += 0.6; carrier += 0.4; } else { carrier += 0.6; node += 0.4; } }
                }
                case OutcomeData.FulfillmentOutcome f -> {
                    if (f.storeRejected()) node += 1;
                    if (f.packageCount() > 1) stock += 1;
                }
                case OutcomeData.InventoryOutcome inv -> { if (inv.stranded() || inv.backorderCancelled()) stock += 1; }
                case OutcomeData.PaymentOutcome p -> { if (p.chargeback() || p.refused()) customer += 1; }
                case OutcomeData.ReturnOutcome r -> { if (r.fraudConfirmed()) customer += 1; }
                case OutcomeData.ContactOutcome c -> { }
            }
        }
        double total = stock + node + carrier + customer;
        Map<String, Double> out = new LinkedHashMap<>();
        if (total <= 0) return out;
        out.put("attr.stock", round(stock / total));
        out.put("attr.node", round(node / total));
        out.put("attr.carrier", round(carrier / total));
        out.put("attr.customer", round(customer / total));
        return out;
    }

    /** The party carrying the largest share, or null when nothing went wrong. */
    public static String primary(Map<String, Double> attribution) {
        return attribution.entrySet().stream().filter(e -> e.getKey().startsWith("attr.")).max(Map.Entry.comparingByValue())
            .map(e -> e.getKey().substring("attr.".length())).orElse(null);
    }

    private static double round(double d) { return Math.round(d * 1000) / 1000d; }
}
