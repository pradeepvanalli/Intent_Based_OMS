package ai.intentoms.integrations.notify;

import ai.intentoms.learning.explain.LearningDigestSender;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * XP-04: emails the weekly learning digest through SendGrid when it is configured. Every attempt is logged in
 * {@code notification_log} (template {@code learning_digest}); recipients are staff addresses from tenant config,
 * never customers.
 */
@Component
public class LearningDigestEmailSender implements LearningDigestSender {
    private final SendGridEmailSender email;
    private final NotificationLogRepository log;

    public LearningDigestEmailSender(SendGridEmailSender email, NotificationLogRepository log) {
        this.email = email;
        this.log = log;
    }

    @Override public String channel() { return "email"; }

    @Override
    public List<String> send(List<String> recipients, String subject, String body) {
        List<String> delivered = new ArrayList<>();
        for (String to : recipients) {
            if (!email.configured()) {
                log.insert(null, "email", "learning_digest", null, CompositeCustomerNotifier.STATUS_SKIPPED_NO_PROVIDER, null, null, null, null, Instant.now());
                continue;
            }
            ProviderResult r = email.send(to, subject, body);
            log.insert(null, "email", "learning_digest", null, r.sent() ? CompositeCustomerNotifier.STATUS_SENT : CompositeCustomerNotifier.STATUS_FAILED,
                SendGridEmailSender.PROVIDER, r.providerRef(), null, r.error(), Instant.now());
            if (r.sent()) delivered.add(to);
        }
        return delivered;
    }
}
