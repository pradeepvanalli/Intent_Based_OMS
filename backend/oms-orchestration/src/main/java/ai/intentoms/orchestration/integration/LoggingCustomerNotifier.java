package ai.intentoms.orchestration.integration;

import ai.intentoms.order.model.Communication;
import ai.intentoms.order.service.CommunicationProvider;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Default {@link CustomerNotifier}: stores the message in {@code customer_communication} and logs it; also the module's
 * {@link CommunicationProvider} so the order view (FR-CS-01) lists every message sent. Replace with an ESP/SMS adapter by
 * registering another {@code CustomerNotifier} bean with {@code @Primary}.
 */
@Component
public class LoggingCustomerNotifier implements CustomerNotifier, CommunicationProvider {
    private static final Logger log = LoggerFactory.getLogger(LoggingCustomerNotifier.class);
    private final CustomerCommunicationRepository repo;
    private final TimeSource time;
    private final AuthorizationService authz;

    public LoggingCustomerNotifier(CustomerCommunicationRepository repo, TimeSource time, AuthorizationService authz) {
        this.repo = repo;
        this.time = time;
        this.authz = authz;
    }

    @Override
    public String notify(String orderId, String channel, String template, String subject, String summary, Map<String, Object> context) {
        String id = Ids.prefixed("com");
        String ch = channel == null || channel.isBlank() ? "email" : channel.toLowerCase();
        repo.insert(id, orderId, ch, "outbound", template, subject, summary, authz.actorRef(), time.now());
        log.info("Customer message {} [{}:{}] for order {}: {} — {}", id, ch, template, orderId, subject, summary);
        return id;
    }

    @Override
    public List<Communication> communicationsFor(String orderId) { return repo.forOrder(orderId); }
}
