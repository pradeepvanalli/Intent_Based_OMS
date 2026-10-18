package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FD-06: records chargebacks reported by the payment gateway against the payment they belong to, once per gateway
 * dispute id. A new dispute appends {@code payment.chargeback} (the order's episodes get a payment outcome and its
 * fraud decisions a label); a dispute on an AI agent's order also appends {@code agent.dispute_recorded} to that
 * agent, which feeds its dispute rate (FD-05). A later notice that closes the dispute appends
 * {@code payment.chargeback_won} or {@code payment.chargeback_lost}. Retried webhooks change nothing.
 */
@Service
public class ChargebackService {
    private static final Logger log = LoggerFactory.getLogger(ChargebackService.class);
    /** Gateway reasons that mean the cardholder did not authorise the payment (Stripe's codes). */
    static final Set<String> FRAUD_REASONS = Set.of("fraudulent", "unrecognized", "card_not_present_fraud");

    private final PaymentRepository repo;
    private final EventStore events;
    private final TimeSource time;
    private final TransactionTemplate tx;
    private final Metrics metrics;

    public ChargebackService(PaymentRepository repo, EventStore events, TimeSource time, TransactionTemplate tx, Metrics metrics) {
        this.repo = repo;
        this.events = events;
        this.time = time;
        this.tx = tx;
        this.metrics = metrics;
    }

    /**
     * @param paymentRef gateway reference (payment intent / charge id) or the OMS payment id
     * @param amount     disputed amount; null means the payment's authorised amount
     * @param status     OPEN, WON or LOST
     */
    public record Notice(String psp, String pspDisputeId, String paymentRef, Money amount, String reason, String status) {}

    public Map<String, Object> record(Notice n) {
        Optional<Payment> payment = repo.findByPspRef(n.paymentRef()).or(() -> repo.find(n.paymentRef()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pspDisputeId", n.pspDisputeId());
        if (payment.isEmpty()) {
            metrics.count("oms.finance.chargeback", "matched", "false");
            log.warn("Chargeback {} references unknown payment {}", n.pspDisputeId(), n.paymentRef());
            out.put("matched", false);
            return out;
        }
        Payment p = payment.get();
        String reason = n.reason() == null ? null : n.reason().toLowerCase(Locale.ROOT);
        boolean fraud = reason != null && FRAUD_REASONS.contains(reason);
        String agentId = p.agentMandate() == null || p.agentMandate().get("agentId") == null ? null : String.valueOf(p.agentMandate().get("agentId"));
        Money amount = n.amount() != null ? n.amount() : p.amountAuthorized();
        String status = n.status() == null ? PaymentDispute.OPEN : n.status().toUpperCase(Locale.ROOT);
        Actor actor = Actor.system(n.psp() + "-webhook");
        return tx.execute(s -> {
            Optional<PaymentDispute> before = repo.findDispute(n.psp(), n.pspDisputeId());
            PaymentDispute d = new PaymentDispute(Ids.prefixed("dsp"), p.id(), p.orderId(), n.psp(), n.pspDisputeId(), amount, reason, status, fraud, agentId, time.now());
            boolean created = repo.upsertDispute(d);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", p.id());
            payload.put("orderId", p.orderId());
            payload.put("pspDisputeId", n.pspDisputeId());
            payload.put("amount", amount.amount());
            payload.put("currency", amount.currency());
            payload.put("reasonCode", reason);
            payload.put("fraud", fraud);
            payload.put("status", status);
            if (agentId != null) payload.put("agentId", agentId);
            if (created) {
                append("payment", p.id(), "payment.chargeback", payload, actor, p.orderId());
                if (agentId != null) append("agent", agentId, "agent.dispute_recorded", payload, actor, p.orderId());
                metrics.count("oms.finance.chargeback", "matched", "true", "fraud", String.valueOf(fraud));
            } else if (before.isPresent() && !before.get().status().equals(status) && !PaymentDispute.OPEN.equals(status)) {
                append("payment", p.id(), PaymentDispute.WON.equals(status) ? "payment.chargeback_won" : "payment.chargeback_lost", payload, actor, p.orderId());
            }
            out.put("matched", true);
            out.put("created", created);
            out.put("paymentId", p.id());
            out.put("orderId", p.orderId());
            out.put("fraud", fraud);
            out.put("status", status);
            return out;
        });
    }

    private void append(String aggregate, String id, String type, Map<String, Object> payload, Actor actor, String orderId) {
        events.append(aggregate, id, new NewEvent(type, payload, actor, null, orderId, Map.of("orderId", orderId)));
    }
}
