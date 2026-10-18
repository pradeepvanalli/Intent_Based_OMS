package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * OC-03: turns real ledger events into the typed outcome catalog and attaches them to every open or provisional
 * episode for the order. The mapping below covers the event types confirmed in the current codebase
 * (order/payment/refund/return/cs.*); a module emitting a new outcome-relevant event type only needs to add one
 * case here, not touch the order path. Chargeback and stranded-inventory signals aren't emitted anywhere yet
 * (confirmed absent at the time this was built) — FD-06 and IU-04's learners should wire those sources directly
 * once the originating module emits them, rather than guessing a shape here.
 */
@Component
public class OutcomeObserver implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(OutcomeObserver.class);

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;

    public OutcomeObserver(EpisodeRepository episodes, OutcomeRepository outcomes) {
        this.episodes = episodes;
        this.outcomes = outcomes;
    }

    @Override
    public List<String> eventTypePrefixes() {
        return List.of("order.delivered", "order.shipped", "order.out_for_delivery", "event.delivered", "event.shipped",
            "refund.issued", "refund.failed", "payment.failed", "return.dispositioned", "oms.returns.disposition", "oms.returns.refunded", "cs.wismo");
    }

    @Override
    public void on(Event e) {
        OutcomeData data = map(e);
        if (data == null) return;
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            String orderId = orderIdOf(e);
            if (orderId == null) return;
            List<Episode> open = episodes.forOrder(orderId).stream().filter(ep -> ep.status() != EpisodeStatus.MATURE).toList();
            for (Episode ep : open) {
                if (outcomes.existsForEvent(e.id())) continue;
                outcomes.insert(OutcomeRecord.of(Ids.prefixed("out"), ep.episodeId(), data, e.occurredAt(), e.id(), Instant.now()));
            }
        } catch (RuntimeException ex) {
            log.warn("Outcome observation failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    private static OutcomeData map(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        return switch (e.type()) {
            case "order.delivered", "event.delivered" -> new OutcomeData.DeliveryOutcome(
                bool(p.get("onTime"), true), num(p.get("lateByHours"), 0).longValue(), bool(p.get("damaged"), false), bool(p.get("lost"), false));
            case "order.shipped", "event.shipped" -> new OutcomeData.FulfillmentOutcome(
                num(p.get("packageCount"), 1).intValue(), bool(p.get("splitAvoided"), false), List.of(), bool(p.get("storeRejected"), false));
            case "refund.issued", "oms.returns.refunded" -> new OutcomeData.PaymentOutcome(false, false, num(p.get("amount"), 0).doubleValue());
            case "refund.failed", "payment.failed" -> new OutcomeData.PaymentOutcome(false, true, num(p.get("amount"), 0).doubleValue());
            case "return.dispositioned", "oms.returns.disposition" -> new OutcomeData.ReturnOutcome(
                str(p.get("reasonCode")), str(p.get("disposition")), bool(p.get("fraudConfirmed"), false), num(p.get("refundAmount"), 0).doubleValue());
            case "cs.wismo" -> new OutcomeData.ContactOutcome("cs", "wismo", true);
            default -> null;
        };
    }

    private static String orderIdOf(Event e) {
        if (e.payload() != null) {
            Object o = e.payload().getOrDefault("orderId", e.payload().get("order_id"));
            if (o != null) return String.valueOf(o);
        }
        return "order".equals(e.aggregateType()) ? e.aggregateId() : e.correlationId();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static boolean bool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }
    private static Number num(Object o, double def) { return o instanceof Number n ? n : def; }
}
