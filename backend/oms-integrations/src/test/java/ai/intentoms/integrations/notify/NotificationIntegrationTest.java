package ai.intentoms.integrations.notify;

import ai.intentoms.integrations.IntegrationsTestBase;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.orchestration.integration.LoggingCustomerNotifier;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Section 11 Notifications: the composite notifier is primary, renders templates, sends via SendGrid / Twilio (WireMock), logs, and keeps customer_communication. */
class NotificationIntegrationTest extends IntegrationsTestBase {
    @Autowired CustomerNotifier notifier;
    @Autowired LoggingCustomerNotifier communications;
    @Autowired NotificationLogRepository log;
    @Autowired NotificationController controller;

    @Test
    void compositeIsThePrimaryNotifier() { assertThat(notifier).isInstanceOf(CompositeCustomerNotifier.class); }

    @Test
    void emailGoesThroughSendGridAndIsLogged() {
        WM.stubFor(post(urlEqualTo("/v3/mail/send")).willReturn(aResponse().withStatus(202).withHeader("X-Message-Id", "sg-msg-1")));
        Order o = capture(webOrder("ntf-1"));
        String comId = notifier.notify(o.id(), "email", "shipped", null, null, Map.of("carrier", "UPS", "tracking", "1Z999AA10123456784", "eta", "2026-10-09"));

        WM.verify(postRequestedFor(urlEqualTo("/v3/mail/send")).withHeader("Authorization", equalTo("Bearer SG.test-key"))
            .withRequestBody(matchingJsonPath("$.personalizations[0].to[0].email", equalTo("dana@example.com")))
            .withRequestBody(matchingJsonPath("$.from.email", equalTo("orders@test.example")))
            .withRequestBody(matchingJsonPath("$.subject", equalTo("Intent OMS: order ntf-1 has shipped")))
            .withRequestBody(matchingJsonPath("$.content[0].value", containing("1Z999AA10123456784")))
            .withRequestBody(matchingJsonPath("$.content[0].value", containing("Hi Dana Whitfield")))
            .withRequestBody(matchingJsonPath("$.content[0].value", containing("2 × Fleece hoodie grey M"))));
        List<NotificationLogRepository.Entry> rows = log.forOrder(o.id());
        assertThat(rows).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("sent");
            assertThat(e.provider()).isEqualTo("sendgrid");
            assertThat(e.providerRef()).isEqualTo("sg-msg-1");
            assertThat(e.template()).isEqualTo("shipped");
            assertThat(e.channel()).isEqualTo("email");
            assertThat(e.recipient()).startsWith("em_").doesNotContain("@");
            assertThat(e.communicationId()).isEqualTo(comId);
        });
        assertThat(communications.communicationsFor(o.id())).singleElement().satisfies(c -> { assertThat(c.id()).isEqualTo(comId); assertThat(c.channel()).isEqualTo("email"); });
        assertThat(controller.list(o.id(), 10)).hasSize(1);
    }

    @Test
    void smsGoesThroughTwilioUsingTheTenantDefaultChannel() {
        config.set(IntegrationsConfigKeys.NOTIFY_DEFAULT_CHANNEL, Json.write("sms"));
        config.set(IntegrationsConfigKeys.NOTIFY_BRAND, Json.write("Acme Outfitters"));
        WM.stubFor(post(urlPathEqualTo("/2010-04-01/Accounts/ACtest/Messages.json")).willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"sid\":\"SM123\",\"status\":\"queued\"}")));
        Order o = capture(webOrder("ntf-2"));
        notifier.notify(o.id(), null, "pickup_reminder", null, null, Map.of("store_name", "State St", "store_address", "1 N State St, Chicago", "pickup_by", "Friday 6 pm"));

        WM.verify(postRequestedFor(urlPathEqualTo("/2010-04-01/Accounts/ACtest/Messages.json"))
            .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("ACtest", "tok"))
            .withRequestBody(containing("To=%2B15551234567")).withRequestBody(containing("From=%2B15550000000"))
            .withRequestBody(containing("State+St")).withRequestBody(containing("Friday+6+pm")).withRequestBody(containing("Acme+Outfitters")));
        assertThat(log.forOrder(o.id())).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("sent");
            assertThat(e.provider()).isEqualTo("twilio");
            assertThat(e.providerRef()).isEqualTo("SM123");
            assertThat(e.channel()).isEqualTo("sms");
            assertThat(e.recipient()).startsWith("ph_");
        });
    }

    @Test
    void providerErrorsAreLoggedAsFailedAndNeverThrow() {
        WM.stubFor(post(urlEqualTo("/v3/mail/send")).willReturn(aResponse().withStatus(401).withBody("{\"errors\":[{\"message\":\"bad key\"}]}")));
        Order o = capture(webOrder("ntf-3"));
        String comId = notifier.notify(o.id(), "email", "delivered", null, null, Map.of("delivered_at", "2026-10-08"));
        assertThat(comId).isNotBlank();
        assertThat(log.forOrder(o.id())).singleElement().satisfies(e -> { assertThat(e.status()).isEqualTo("failed"); assertThat(e.error()).contains("401"); });
        assertThat(communications.communicationsFor(o.id())).hasSize(1);
    }

    @Test
    void unknownTemplateFallsBackToGenericWithCodeAuthoredSubjectAndSummary() {
        WM.stubFor(post(urlEqualTo("/v3/mail/send")).willReturn(aResponse().withStatus(202).withHeader("X-Message-Id", "sg-2")));
        Order o = capture(webOrder("ntf-4"));
        notifier.notify(o.id(), "email", "custom_playbook_message", "We are looking into your order", "Our team will update you within 24 hours.", Map.of());
        WM.verify(postRequestedFor(urlEqualTo("/v3/mail/send"))
            .withRequestBody(matchingJsonPath("$.subject", equalTo("We are looking into your order")))
            .withRequestBody(matchingJsonPath("$.content[0].value", containing("within 24 hours"))));
        assertThat(log.forOrder(o.id())).singleElement().satisfies(e -> assertThat(e.template()).isEqualTo("generic"));
    }
}
