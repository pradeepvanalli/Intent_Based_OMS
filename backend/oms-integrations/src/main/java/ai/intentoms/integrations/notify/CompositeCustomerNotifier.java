package ai.intentoms.integrations.notify;

import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.orchestration.integration.LoggingCustomerNotifier;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Production {@link CustomerNotifier} (section 11 Notifications P0: Twilio, SendGrid; FR-MON-04, FR-CS-01). Installed as
 * the primary notifier when {@code oms.integrations.notify.enabled=true}:
 * <ol>
 *   <li>renders the template from the caller's context map of order facts ({@link NotificationTemplates}, GR-15);</li>
 *   <li>picks the channel: the caller's, else {@code tenant_config notify.default_channel}, else {@code email}; falls back
 *       to the other channel when the customer has no contact for the chosen one;</li>
 *   <li>sends through {@link SendGridEmailSender} / {@link TwilioSmsSender} only when that provider has credentials —
 *       otherwise the attempt is logged as {@code skipped_no_provider} and nothing leaves the system;</li>
 *   <li>always delegates to {@link LoggingCustomerNotifier} so {@code customer_communication} (the order view's
 *       communications list) keeps working, and records the attempt in {@code notification_log} with the recipient tokenised.</li>
 * </ol>
 * Recipients come from the context ({@code email} / {@code phone}) or the order's customer record; never from a model.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "oms.integrations.notify", name = "enabled", havingValue = "true")
public class CompositeCustomerNotifier implements CustomerNotifier {
    private static final Logger log = LoggerFactory.getLogger(CompositeCustomerNotifier.class);
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED_NO_PROVIDER = "skipped_no_provider";
    public static final String STATUS_SKIPPED_NO_RECIPIENT = "skipped_no_recipient";

    private final NotificationTemplates templates;
    private final SendGridEmailSender email;
    private final TwilioSmsSender sms;
    private final NotificationLogRepository log_;
    private final LoggingCustomerNotifier communications;
    private final OrderService orders;
    private final TenantConfigStore config;
    private final Tokenizer tokenizer;
    private final TimeSource time;
    private final Metrics metrics;

    public CompositeCustomerNotifier(NotificationTemplates templates, SendGridEmailSender email, TwilioSmsSender sms, NotificationLogRepository log_,
                                     LoggingCustomerNotifier communications, OrderService orders, TenantConfigStore config, Tokenizer tokenizer, TimeSource time, Metrics metrics) {
        this.templates = templates;
        this.email = email;
        this.sms = sms;
        this.log_ = log_;
        this.communications = communications;
        this.orders = orders;
        this.config = config;
        this.tokenizer = tokenizer;
        this.time = time;
        this.metrics = metrics;
    }

    @Override
    public String notify(String orderId, String channel, String template, String subject, String summary, Map<String, Object> context) {
        Map<String, Object> ctx = facts(orderId, context, subject, summary);
        String tpl = templates.has(template) ? template : NotificationTemplates.GENERIC;
        NotificationTemplates.Rendered rendered = templates.render(tpl, ctx);
        if (!rendered.missingSlots().isEmpty()) log.debug("Template {} for order {} has unfilled slots {}", tpl, orderId, rendered.missingSlots());
        String recipientEmail = str(ctx.get("email"));
        String recipientPhone = str(ctx.get("phone"));
        String ch = channel(channel, recipientEmail, recipientPhone);
        // The order view keeps seeing the message exactly as before (customer_communication).
        String communicationId = communications.notify(orderId, ch, template, firstNonBlank(subject, rendered.subject()), firstNonBlank(summary, rendered.body()), context);

        String recipient = "sms".equals(ch) ? recipientPhone : recipientEmail;
        String status; String provider = null; String ref = null; String error = null;
        if (recipient == null) {
            status = STATUS_SKIPPED_NO_RECIPIENT;
        } else if ("sms".equals(ch)) {
            provider = TwilioSmsSender.PROVIDER;
            if (!sms.configured()) status = STATUS_SKIPPED_NO_PROVIDER;
            else { ProviderResult r = sms.send(recipient, rendered.body()); status = r.sent() ? STATUS_SENT : STATUS_FAILED; ref = r.providerRef(); error = r.error(); }
        } else {
            provider = SendGridEmailSender.PROVIDER;
            if (!email.configured()) status = STATUS_SKIPPED_NO_PROVIDER;
            else { ProviderResult r = email.send(recipient, rendered.subject(), rendered.body()); status = r.sent() ? STATUS_SENT : STATUS_FAILED; ref = r.providerRef(); error = r.error(); }
        }
        String token = recipient == null ? null : "sms".equals(ch) ? tokenizer.phone(recipient) : tokenizer.email(recipient);
        log_.insert(orderId, ch, tpl, token, status, provider, ref, communicationId, error, time.now());
        metrics.count("oms.integrations.notify", "channel", ch, "template", tpl, "status", status);
        log.info("Notification {} [{}:{}] for order {} → {}{}", communicationId, ch, tpl, orderId, status, error == null ? "" : " (" + error + ")");
        return communicationId;
    }

    /** Context facts + order facts the templates need; caller values win over order-derived ones. */
    Map<String, Object> facts(String orderId, Map<String, Object> context, String subject, String summary) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("brand", config.getString(IntegrationsConfigKeys.NOTIFY_BRAND, "Intent OMS"));
        if (orderId != null) {
            ctx.put("order_id", orderId);
            try {
                Order o = orders.get(orderId);
                ctx.put("order_number", o.channelOrderId());
                if (o.placedAt() != null) ctx.put("placed_at", o.placedAt().toString().substring(0, 10));
                if (o.customer() != null) {
                    if (o.customer().name() != null) ctx.put("customer_name", o.customer().name());
                    if (o.customer().email() != null) ctx.put("email", o.customer().email());
                    if (o.customer().phone() != null) ctx.put("phone", o.customer().phone());
                }
                o.shipTo().ifPresent(a -> { ctx.put("address", a.oneLine()); if (a.phone() != null) ctx.putIfAbsent("phone", a.phone()); });
                ctx.put("item_summary", o.lines().stream().map(l -> l.qty() + " × " + (l.description() == null ? l.sku() : l.description())).reduce((a, b) -> a + ", " + b).orElse(""));
                if (o.totals() != null && o.totals().grandTotal() != null) ctx.put("order_total", o.totals().grandTotal().toString());
                o.fulfilmentGroups().stream().filter(g -> g.promiseDate() != null).findFirst().ifPresent(g -> ctx.put("promise_date", g.promiseDate().toString()));
            } catch (NotFoundException e) { log.debug("Notification for unknown order {}", orderId); }
        }
        if (subject != null) ctx.put("subject", subject);
        if (summary != null) ctx.put("summary", summary);
        if (context != null) context.forEach((k, v) -> { if (v != null) ctx.put(k, v); });
        ctx.putIfAbsent("customer_name", "there");
        ctx.putIfAbsent("order_number", orderId == null ? "" : orderId);
        return ctx;
    }

    String channel(String requested, String recipientEmail, String recipientPhone) {
        String ch = requested == null || requested.isBlank() ? config.getString(IntegrationsConfigKeys.NOTIFY_DEFAULT_CHANNEL, "email") : requested;
        ch = ch.trim().toLowerCase(Locale.ROOT);
        if (!"sms".equals(ch) && !"email".equals(ch)) ch = "email";
        if ("sms".equals(ch) && recipientPhone == null && recipientEmail != null) return "email";
        if ("email".equals(ch) && recipientEmail == null && recipientPhone != null) return "sms";
        return ch;
    }

    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim(); }
    private static String firstNonBlank(String a, String b) { return a != null && !a.isBlank() ? a : b; }
}
