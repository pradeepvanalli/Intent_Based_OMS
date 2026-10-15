package ai.intentoms.orchestration.integration;

import java.util.Map;

/**
 * SPI for outbound customer messages (FR-MON-04 {@code contact_customer}, FR-CS-01 communications, BOPIS ready /
 * shipped / delivered notices). Implementations must record the message so the order view can show it; the default
 * {@link LoggingCustomerNotifier} writes {@code customer_communication} and logs.
 */
public interface CustomerNotifier {
    /**
     * @param orderId  order the message concerns (may be null for account-level messages)
     * @param channel  email | sms
     * @param template template key (shipped, delivered, delay_notice, address_confirm, return_reminder, pickup_reminder, ready_for_pickup, ...)
     * @param context  typed slots rendered into the template (GR-15: never free model text)
     * @return the stored communication id
     */
    String notify(String orderId, String channel, String template, String subject, String summary, Map<String, Object> context);
}
