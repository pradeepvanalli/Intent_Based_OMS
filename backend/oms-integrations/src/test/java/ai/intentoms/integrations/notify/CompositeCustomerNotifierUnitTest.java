package ai.intentoms.integrations.notify;

import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.orchestration.integration.LoggingCustomerNotifier;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.platform.time.TimeSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Template rendering (GR-15: slots only from the facts map) and the skipped_no_provider / skipped_no_recipient paths without any provider credentials. */
class CompositeCustomerNotifierUnitTest {
    NotificationTemplates templates = new NotificationTemplates();
    Metrics metrics = new Metrics(new SimpleMeterRegistry());
    SendGridEmailSender email = new SendGridEmailSender(new IntegrationsProperties.SendGrid(null, "http://localhost:1", "a@b.c", "x"), Duration.ofSeconds(1), RestClient.builder(), metrics);
    TwilioSmsSender sms = new TwilioSmsSender(new IntegrationsProperties.Twilio(null, null, null, "http://localhost:1"), Duration.ofSeconds(1), RestClient.builder(), metrics);
    NotificationLogRepository log = mock(NotificationLogRepository.class);
    LoggingCustomerNotifier communications = mock(LoggingCustomerNotifier.class);
    OrderService orders = mock(OrderService.class);
    TenantConfigStore config = mock(TenantConfigStore.class);
    Tokenizer tokenizer = mock(Tokenizer.class);
    CompositeCustomerNotifier notifier = new CompositeCustomerNotifier(templates, email, sms, log, communications, orders, config, tokenizer, new TimeSource(), metrics);

    @Test
    void templatesRenderOnlyFromFacts() {
        assertThat(templates.keys()).contains("shipped", "delivered", "delay_notice", "address_confirm", "return_reminder", "pickup_reminder", "resourced", "store_backlog", "refund_issued", "order_confirmed", "ready_for_pickup", "generic");
        NotificationTemplates.Rendered r = templates.render("delay_notice", Map.of("brand", "Acme", "customer_name", "Sam", "order_number", "W-1", "new_promise_date", "Oct 9", "old_promise_date", "Oct 7", "reason", "carrier delay"));
        assertThat(r.subject()).isEqualTo("Acme: an update on order W-1");
        assertThat(r.body()).contains("Hi Sam").contains("Oct 9").contains("previously Oct 7").contains("carrier delay").doesNotContain("{{");
        assertThat(r.missingSlots()).isEmpty();
        NotificationTemplates.Rendered partial = templates.render("shipped", Map.of("order_number", "W-2", "nested", Map.of("x", 1)));
        assertThat(partial.missingSlots()).contains("brand", "customer_name", "carrier", "tracking", "eta", "item_summary");
        assertThat(partial.body()).doesNotContain("{{").doesNotContain("x=1");
        assertThat(templates.get("shipped").slots()).contains("tracking", "carrier");
    }

    @Test
    void noProviderCredentialsMeansSkippedNoProvider() {
        when(config.getString(eq(IntegrationsConfigKeys.NOTIFY_BRAND), anyString())).thenReturn("Acme");
        when(config.getString(eq(IntegrationsConfigKeys.NOTIFY_DEFAULT_CHANNEL), anyString())).thenReturn("email");
        when(orders.get("ord_1")).thenThrow(new NotFoundException("no order"));
        when(communications.notify(any(), any(), any(), any(), any(), any())).thenReturn("com_1");
        when(tokenizer.email("sam@example.com")).thenReturn("em_abc");

        String id = notifier.notify("ord_1", null, "shipped", null, null, Map.of("email", "sam@example.com", "customer_name", "Sam", "tracking", "1Z"));
        assertThat(id).isEqualTo("com_1");
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(log).insert(eq("ord_1"), eq("email"), eq("shipped"), eq("em_abc"), status.capture(), eq("sendgrid"), isNull(), eq("com_1"), isNull(), any());
        assertThat(status.getValue()).isEqualTo(CompositeCustomerNotifier.STATUS_SKIPPED_NO_PROVIDER);

        notifier.notify("ord_1", "sms", "shipped", null, null, Map.of("customer_name", "Sam"));
        verify(log).insert(eq("ord_1"), eq("sms"), eq("shipped"), isNull(), eq(CompositeCustomerNotifier.STATUS_SKIPPED_NO_RECIPIENT), isNull(), isNull(), eq("com_1"), isNull(), any());
    }

    @Test
    void channelFallsBackToTheContactTheCustomerHas() {
        when(config.getString(eq(IntegrationsConfigKeys.NOTIFY_DEFAULT_CHANNEL), anyString())).thenReturn("sms");
        assertThat(notifier.channel(null, "a@b.c", null)).isEqualTo("email");
        assertThat(notifier.channel(null, "a@b.c", "+1555")).isEqualTo("sms");
        assertThat(notifier.channel("email", null, "+1555")).isEqualTo("sms");
        assertThat(notifier.channel("fax", "a@b.c", null)).isEqualTo("email");
    }
}
