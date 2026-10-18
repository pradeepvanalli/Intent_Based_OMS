package ai.intentoms.orchestration.cx;

import ai.intentoms.order.model.Order;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.orchestration.actions.RepromiseAction;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CX-03: acts on {@code order.delay_warning} (the breach-risk score first reached the warn-early threshold). Re-promises
 * the order through {@link RepromiseAction}, which tells the customer when the date moved later; when the date held,
 * the customer still gets a heads-up ({@code delay_risk} template) so they hear about a likely delay from us first.
 */
@Component
public class ProactiveDelayNoticeSubscriber implements EventSubscriber {
    public static final String TEMPLATE = "delay_risk";
    private static final Logger log = LoggerFactory.getLogger(ProactiveDelayNoticeSubscriber.class);

    private final OrderRepository orders;
    private final RepromiseAction repromise;
    private final CustomerNotifier notifier;

    public ProactiveDelayNoticeSubscriber(OrderRepository orders, RepromiseAction repromise, CustomerNotifier notifier) {
        this.orders = orders;
        this.repromise = repromise;
        this.notifier = notifier;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("order.delay_warning"); }

    @Override
    public void on(Event e) {
        var previousTenant = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        Authentication prev = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(TenantContext.require())));
        try {
            Optional<Order> order = orders.find(e.aggregateId());
            if (order.isEmpty()) return;
            Object score = e.payload() == null ? null : e.payload().get("score");
            Object threshold = e.payload() == null ? null : e.payload().get("threshold");
            Map<String, Object> result = repromise.repromise(order.get(), "delay_notice", null, "proactive delay warning: risk " + score + " reached " + threshold);
            if (!Boolean.TRUE.equals(result.get("later"))) {
                notifier.notify(order.get().id(), "email", TEMPLATE, "Your delivery may be delayed",
                    "We are watching order " + order.get().id() + " closely; it may arrive later than promised and we will update you.",
                    Map.of("riskScore", String.valueOf(score)));
            }
        } catch (RuntimeException ex) {
            log.warn("Proactive delay notice failed for order {}: {}", e.aggregateId(), ex.toString());
        } finally {
            if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev);
            previousTenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
