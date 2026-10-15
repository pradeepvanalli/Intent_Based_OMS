package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.returns.model.ReturnEvents;
import ai.intentoms.returns.model.StoreCredit;
import ai.intentoms.returns.store.StoreCreditRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link StoreCreditIssuer}: a {@code store_credit} row plus a {@code store_credit.issued} event on the return
 * aggregate. Registered by {@link ai.intentoms.returns.config.ReturnsAutoConfiguration} when no other issuer bean exists.
 */
public class DefaultStoreCreditIssuer implements StoreCreditIssuer {
    private final StoreCreditRepository repo;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;

    public DefaultStoreCreditIssuer(StoreCreditRepository repo, EventStore events, TimeSource time, Metrics metrics) {
        this.repo = repo;
        this.events = events;
        this.time = time;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public StoreCredit issue(String customerRef, Money amount, String rmaId, String orderId, String reason, Actor actor) {
        StoreCredit credit = repo.insert(new StoreCredit(Ids.prefixed("sc"), customerRef, amount, rmaId, orderId, reason, actor.ref(), time.now()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("storeCreditId", credit.id());
        payload.put("customerRef", customerRef);
        payload.put("amount", amount.amount());
        payload.put("currency", amount.currency());
        payload.put("orderId", orderId);
        payload.put("reason", reason);
        events.append("return", rmaId, -1, java.util.List.of(new NewEvent(ReturnEvents.STORE_CREDIT_ISSUED, payload, actor, null, orderId, Map.of("storeCreditId", credit.id()))));
        metrics.count("oms.returns.store_credit", amount.amount().doubleValue());
        return credit;
    }
}
