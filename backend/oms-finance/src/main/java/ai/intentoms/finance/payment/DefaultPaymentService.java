package ai.intentoms.finance.payment;

import ai.intentoms.finance.guard.SpendCategory;
import ai.intentoms.finance.guard.SpendGuard;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link PaymentService} (FR-FIN-01, FR-FIN-06, FR-RET-04, GR-05).
 *
 * <p>Transaction design: a PSP call is an external side effect that cannot be rolled back, so every payment or
 * refund mutation runs in its own short transaction that (1) row-locks the payment, (2) re-validates balances under
 * the lock, (3) calls the gateway, (4) updates the row and appends the ledger event. A transport fault on the third
 * tender of a split capture therefore leaves the first two correctly recorded as captured.</p>
 */
@Service
public class DefaultPaymentService implements PaymentService {
    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentService.class);
    public static final String AGGREGATE_PAYMENT = "payment";
    public static final String AGGREGATE_REFUND = "refund";

    private final PaymentRepository repo;
    private final PaymentGateway gateway;
    private final EventStore events;
    private final SpendGuard spendGuard;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final TransactionTemplate tx;
    private final Metrics metrics;

    public DefaultPaymentService(PaymentRepository repo, PaymentGateway gateway, EventStore events, SpendGuard spendGuard,
                                 TimeSource time, AuthorizationService authz, TransactionTemplate tx, Metrics metrics) {
        this.repo = repo;
        this.gateway = gateway;
        this.events = events;
        this.spendGuard = spendGuard;
        this.time = time;
        this.authz = authz;
        this.tx = tx;
        this.metrics = metrics;
    }

    @Override
    public Payment authorize(String orderId, Money amount, String tenderToken, String tenderType, Map<String, Object> agentMandate) {
        requireOrder(orderId);
        if (amount == null || !amount.isPositive()) throw new ValidationException("Authorisation amount must be positive");
        if (tenderToken == null || tenderToken.isBlank()) throw new ValidationException("tenderToken is required");
        TenderType tender = TenderType.parse(tenderType);
        if (tender == TenderType.AGENT_TOKEN && (agentMandate == null || agentMandate.isEmpty())) {
            throw new ValidationException("agent_token tenders require an agent mandate (FR-FIN-06)");
        }
        Map<String, Object> mandate = agentMandate == null ? Map.of() : agentMandate;
        String paymentId = Ids.prefixed("pay");
        Actor actor = currentActor();
        return metrics.time("oms.finance.payment.authorize", () -> tx.execute(status -> {
            AuthResult result = gateway.authorize(new AuthRequest(paymentId, orderId, amount, tenderToken, tender.code(), mandate));
            Instant now = time.now();
            Payment p;
            if (result.approved()) {
                p = new Payment(paymentId, orderId, tender.code(), tenderToken, gateway.name(), result.pspRef(), PaymentStatus.AUTHORIZED,
                    amount, Money.zero(amount.currency()), result.authExpiresAt(), mandate, null, now, now);
                repo.insert(p);
                Map<String, Object> payload = basePayload(p);
                payload.put("authExpiresAt", p.authExpiresAt() == null ? null : p.authExpiresAt().toString());
                if (!mandate.isEmpty()) payload.put("agentMandateId", mandate.get("mandateId"));
                append(AGGREGATE_PAYMENT, paymentId, "payment.authorized", payload, actor, orderId);
                metrics.count("oms.finance.payment.authorized", "psp", gateway.name(), "tender", tender.code());
            } else {
                p = new Payment(paymentId, orderId, tender.code(), tenderToken, gateway.name(), null, PaymentStatus.FAILED,
                    amount, Money.zero(amount.currency()), null, mandate, result.failureCode(), now, now);
                repo.insert(p);
                Map<String, Object> payload = basePayload(p);
                payload.put("stage", "authorize");
                payload.put("failureCode", result.failureCode());
                payload.put("failureMessage", result.failureMessage());
                append(AGGREGATE_PAYMENT, paymentId, "payment.failed", payload, actor, orderId);
                metrics.count("oms.finance.payment.failed", "psp", gateway.name(), "stage", "authorize", "code", String.valueOf(result.failureCode()));
                log.info("Authorisation declined for order {} payment {}: {}", orderId, paymentId, result.failureCode());
            }
            return p;
        }));
    }

    @Override
    public List<Payment> reauthorizeIfExpiring(String orderId, Duration within) {
        requireOrder(orderId);
        Instant threshold = time.now().plus(within == null ? Duration.ZERO : within);
        Actor actor = currentActor();
        List<Payment> out = new ArrayList<>();
        for (Payment candidate : repo.forOrder(orderId)) {
            if (!candidate.status().isOpen() || candidate.authExpiresAt() == null || !candidate.authExpiresAt().isBefore(threshold)) continue;
            Payment updated = tx.execute(status -> {
                Payment p = repo.lock(candidate.id());
                if (!p.status().isOpen() || p.authExpiresAt() == null || !p.authExpiresAt().isBefore(threshold)) return null;
                Money remaining = p.uncaptured();
                if (!remaining.isPositive()) return null;
                AuthResult r = gateway.reauthorize(p.pspRef(), remaining);
                Instant now = time.now();
                if (!r.approved()) {
                    Payment failed = new Payment(p.id(), p.orderId(), p.tenderType(), p.tenderToken(), p.psp(), p.pspRef(), PaymentStatus.FAILED,
                        p.amountAuthorized(), p.amountCaptured(), p.authExpiresAt(), p.agentMandate(), r.failureCode(), p.createdAt(), now);
                    repo.update(failed);
                    Map<String, Object> payload = basePayload(failed);
                    payload.put("stage", "reauthorize");
                    payload.put("failureCode", r.failureCode());
                    payload.put("failureMessage", r.failureMessage());
                    append(AGGREGATE_PAYMENT, p.id(), "payment.failed", payload, actor, orderId);
                    metrics.count("oms.finance.payment.failed", "psp", gateway.name(), "stage", "reauthorize", "code", String.valueOf(r.failureCode()));
                    return failed;
                }
                PaymentStatus next = p.amountCaptured().isZero() ? PaymentStatus.REAUTHORIZED : p.status();
                Payment re = new Payment(p.id(), p.orderId(), p.tenderType(), p.tenderToken(), p.psp(), r.pspRef(), next,
                    p.amountAuthorized(), p.amountCaptured(), r.authExpiresAt(), p.agentMandate(), null, p.createdAt(), now);
                repo.update(re);
                Map<String, Object> payload = basePayload(re);
                payload.put("previousPspRef", p.pspRef());
                payload.put("previousExpiresAt", p.authExpiresAt().toString());
                payload.put("authExpiresAt", re.authExpiresAt() == null ? null : re.authExpiresAt().toString());
                payload.put("amount", remaining.amount());
                append(AGGREGATE_PAYMENT, p.id(), "payment.reauthorized", payload, actor, orderId);
                metrics.count("oms.finance.payment.reauthorized", "psp", gateway.name());
                return re;
            });
            if (updated != null) out.add(updated);
        }
        return out;
    }

    @Override
    public List<Payment> capture(String orderId, Money amount, String reason) {
        requireOrder(orderId);
        if (amount == null || !amount.isPositive()) throw new ValidationException("Capture amount must be positive");
        List<Payment> open = repo.forOrder(orderId).stream()
            .filter(p -> p.status().isOpen() && p.currency().equals(amount.currency()) && p.uncaptured().isPositive()).toList();
        Money available = open.stream().map(Payment::uncaptured).reduce(Money.zero(amount.currency()), Money::add);
        if (available.compareTo(amount) < 0) {
            throw new ConflictException("Insufficient authorised funds on order " + orderId + ": " + available + " available, " + amount + " requested",
                Map.of("orderId", orderId, "available", available.amount(), "requested", amount.amount(), "currency", amount.currency()));
        }
        Actor actor = currentActor();
        List<Payment> touched = new ArrayList<>();
        Money remaining = amount;
        for (Payment candidate : open) {
            if (!remaining.isPositive()) break;
            final Money want = remaining;
            Payment result = tx.execute(status -> {
                Payment p = repo.lock(candidate.id());
                if (!p.status().isOpen()) return null;
                Money take = want.min(p.uncaptured());
                if (!take.isPositive()) return null;
                String captureId = Ids.prefixed("cap");
                CaptureResult cr = gateway.capture(p.pspRef(), take, captureId);
                Instant now = time.now();
                if (!cr.succeeded()) {
                    Payment failed = new Payment(p.id(), p.orderId(), p.tenderType(), p.tenderToken(), p.psp(), p.pspRef(), PaymentStatus.FAILED,
                        p.amountAuthorized(), p.amountCaptured(), p.authExpiresAt(), p.agentMandate(), cr.failureCode(), p.createdAt(), now);
                    repo.update(failed);
                    Map<String, Object> payload = basePayload(failed);
                    payload.put("stage", "capture");
                    payload.put("captureId", captureId);
                    payload.put("amount", take.amount());
                    payload.put("failureCode", cr.failureCode());
                    payload.put("failureMessage", cr.failureMessage());
                    append(AGGREGATE_PAYMENT, p.id(), "payment.failed", payload, actor, orderId);
                    metrics.count("oms.finance.payment.failed", "psp", gateway.name(), "stage", "capture", "code", String.valueOf(cr.failureCode()));
                    log.warn("Capture of {} on payment {} (order {}) failed: {}", take, p.id(), orderId, cr.failureCode());
                    return failed;
                }
                Money captured = p.amountCaptured().add(cr.captured());
                PaymentStatus next = captured.compareTo(p.amountAuthorized()) >= 0 ? PaymentStatus.CAPTURED : PaymentStatus.PARTIALLY_CAPTURED;
                Payment updated = new Payment(p.id(), p.orderId(), p.tenderType(), p.tenderToken(), p.psp(), p.pspRef(), next,
                    p.amountAuthorized(), captured, p.authExpiresAt(), p.agentMandate(), null, p.createdAt(), now);
                repo.update(updated);
                Map<String, Object> payload = basePayload(updated);
                payload.put("captureId", captureId);
                payload.put("capturePspRef", cr.pspRef());
                payload.put("amount", cr.captured().amount());
                payload.put("reason", reason);
                append(AGGREGATE_PAYMENT, p.id(), "payment.captured", payload, actor, orderId);
                metrics.count("oms.finance.payment.captured", "psp", gateway.name(), "partial", String.valueOf(next == PaymentStatus.PARTIALLY_CAPTURED));
                return updated;
            });
            if (result == null) continue;
            touched.add(result);
            if (result.status() != PaymentStatus.FAILED) {
                remaining = remaining.subtract(result.amountCaptured().subtract(candidate.amountCaptured()));
            }
        }
        if (remaining.isPositive()) {
            log.warn("Capture on order {} left {} uncaptured after gateway failures", orderId, remaining);
        }
        return touched;
    }

    @Override
    public List<Refund> refund(String orderId, Money amount, String reason, String returnId, Actor actor) {
        requireOrder(orderId);
        if (amount == null || !amount.isPositive()) throw new ValidationException("Refund amount must be positive");
        Actor who = actor == null ? currentActor() : actor;
        String agentId = "agent".equals(who.type()) ? who.id() : null;
        spendGuard.check(SpendCategory.REFUND, amount, who.ref(), agentId);

        List<Payment> captured = repo.forOrder(orderId).stream()
            .filter(p -> PaymentStatus.REFUNDABLE.contains(p.status()) && p.currency().equals(amount.currency()) && p.amountCaptured().isPositive()).toList();
        List<Money> weights = new ArrayList<>(captured.size());
        List<Money> refundable = new ArrayList<>(captured.size());
        for (Payment p : captured) {
            weights.add(p.amountCaptured());
            refundable.add(p.amountCaptured().subtract(new Money(repo.refundedAgainst(p.id()), p.currency())).max(Money.zero(p.currency())));
        }
        Money totalRefundable = refundable.stream().reduce(Money.zero(amount.currency()), Money::add);
        if (totalRefundable.compareTo(amount) < 0) {
            throw new ConflictException("Insufficient captured funds on order " + orderId + ": " + totalRefundable + " refundable, " + amount + " requested",
                Map.of("orderId", orderId, "refundable", totalRefundable.amount(), "requested", amount.amount(), "currency", amount.currency()));
        }
        List<Money> legs = RefundAllocator.allocate(weights, refundable, amount);
        List<Refund> out = new ArrayList<>();
        for (int i = 0; i < captured.size(); i++) {
            Money leg = legs.get(i);
            if (!leg.isPositive()) continue;
            Payment payment = captured.get(i);
            // Transaction 1: reserve the funds by persisting the PENDING leg under a row lock. Should the gateway call
            // below fail at transport level, the PENDING row survives and keeps counting against the refundable balance,
            // so a retry can never double-refund; reconciliation (FR-RET-04) resolves it with the PSP.
            Refund pending = tx.execute(status -> {
                Payment p = repo.lock(payment.id());
                Money room = p.amountCaptured().subtract(new Money(repo.refundedAgainst(p.id()), p.currency()));
                if (room.compareTo(leg) < 0) {
                    throw new ConflictException("Concurrent refund consumed captured funds on payment " + p.id(),
                        Map.of("paymentId", p.id(), "refundable", room.amount(), "requested", leg.amount()));
                }
                Instant now = time.now();
                Refund r = new Refund(Ids.prefixed("ref"), orderId, p.id(), returnId, leg, reason, RefundStatus.PENDING, null, null, who.ref(), now, now);
                repo.insertRefund(r);
                append(AGGREGATE_REFUND, r.id(), "refund.requested", refundPayload(r), who, orderId);
                return r;
            });
            RefundResult rr = gateway.refund(payment.pspRef(), leg, pending.id(), reason);
            // Transaction 2: record the outcome and, on success, the GR-05 spend.
            Refund fin = tx.execute(status -> {
                Instant done = time.now();
                Refund f;
                if (rr.succeeded()) {
                    f = new Refund(pending.id(), orderId, pending.paymentId(), returnId, leg, reason, RefundStatus.SUCCEEDED, rr.pspRef(), null, who.ref(), pending.createdAt(), done);
                    repo.updateRefund(f);
                    append(AGGREGATE_REFUND, f.id(), "refund.succeeded", refundPayload(f), who, orderId);
                    spendGuard.record(SpendCategory.REFUND, leg, who.ref(), agentId, f.id());
                    metrics.count("oms.finance.refund.succeeded", "psp", gateway.name());
                } else {
                    f = new Refund(pending.id(), orderId, pending.paymentId(), returnId, leg, reason, RefundStatus.FAILED, null, rr.failureCode(), who.ref(), pending.createdAt(), done);
                    repo.updateRefund(f);
                    Map<String, Object> payload = refundPayload(f);
                    payload.put("failureCode", rr.failureCode());
                    payload.put("failureMessage", rr.failureMessage());
                    append(AGGREGATE_REFUND, f.id(), "refund.failed", payload, who, orderId);
                    metrics.count("oms.finance.refund.failed", "psp", gateway.name(), "code", String.valueOf(rr.failureCode()));
                    log.warn("Refund {} of {} on payment {} failed: {}", f.id(), leg, pending.paymentId(), rr.failureCode());
                }
                return f;
            });
            out.add(fin);
        }
        return out;
    }

    @Override
    public void voidAuthorization(String orderId, String reason) {
        requireOrder(orderId);
        Actor actor = currentActor();
        for (Payment candidate : repo.forOrder(orderId)) {
            if (!candidate.status().isOpen() || candidate.amountCaptured().isPositive()) continue;
            tx.executeWithoutResult(status -> {
                Payment p = repo.lock(candidate.id());
                if (!p.status().isOpen() || p.amountCaptured().isPositive()) return;
                gateway.voidAuth(p.pspRef());
                Instant now = time.now();
                Payment voided = new Payment(p.id(), p.orderId(), p.tenderType(), p.tenderToken(), p.psp(), p.pspRef(), PaymentStatus.VOIDED,
                    p.amountAuthorized(), p.amountCaptured(), p.authExpiresAt(), p.agentMandate(), null, p.createdAt(), now);
                repo.update(voided);
                Map<String, Object> payload = basePayload(voided);
                payload.put("reason", reason);
                append(AGGREGATE_PAYMENT, p.id(), "payment.voided", payload, actor, orderId);
                metrics.count("oms.finance.payment.voided", "psp", gateway.name());
            });
        }
    }

    @Override
    public List<Payment> forOrder(String orderId) { return repo.forOrder(orderId); }

    @Override
    public List<Refund> refundsForOrder(String orderId) { return repo.refundsForOrder(orderId); }

    @Override
    public Payment get(String paymentId) { return repo.get(paymentId); }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private void append(String aggregate, String id, String type, Map<String, Object> payload, Actor actor, String orderId) {
        events.append(aggregate, id, new NewEvent(type, payload, actor, null, orderId, Map.of("orderId", orderId)));
    }

    private static Map<String, Object> basePayload(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.id());
        m.put("orderId", p.orderId());
        m.put("tenderType", p.tenderType());
        m.put("psp", p.psp());
        m.put("pspRef", p.pspRef());
        m.put("status", p.status().name());
        m.put("amountAuthorized", p.amountAuthorized().amount());
        m.put("amountCaptured", p.amountCaptured().amount());
        m.put("currency", p.currency());
        return m;
    }

    private static Map<String, Object> refundPayload(Refund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refundId", r.id());
        m.put("orderId", r.orderId());
        m.put("paymentId", r.paymentId());
        m.put("returnId", r.returnId());
        m.put("amount", r.amount().amount());
        m.put("currency", r.amount().currency());
        m.put("reason", r.reason());
        m.put("status", r.status().name());
        m.put("pspRef", r.pspRef());
        m.put("requestedBy", r.requestedBy());
        return m;
    }

    private Actor currentActor() { return Actor.parse(authz.actorRef()); }

    private static void requireOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) throw new ValidationException("orderId is required");
    }
}
