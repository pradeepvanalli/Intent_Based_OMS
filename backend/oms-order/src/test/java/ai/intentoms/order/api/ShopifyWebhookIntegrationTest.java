package ai.intentoms.order.api;

import ai.intentoms.order.OrderTestBase;
import ai.intentoms.order.capture.ShopifyOrderMapperTest;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.UnauthorizedException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.security.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-CAP-01 storefront webhook: HMAC enforcement when a secret is configured and capture through the mapper. */
class ShopifyWebhookIntegrationTest extends OrderTestBase {
    @Autowired ShopifyWebhookController webhook;
    @Autowired AuthorizationService authz;

    @Test
    void capturesShopifyOrderAndReplaysOnSecondDelivery() {
        String body = ShopifyOrderMapperTest.PAYLOAD.replace("5551234", String.valueOf(System.nanoTime()));
        ResponseEntity<Order> first = webhook.orderCreated(body, null, "wh-1", "orders/create", TENANT, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Order o = first.getBody();
        assertThat(o.channel()).isEqualTo("shopify");
        assertThat(o.lines()).hasSize(2);
        assertThat(o.totals().shipping().amount()).isEqualByComparingTo("6.00");
        assertThat(o.totals().discounts().amount()).isEqualByComparingTo("8.00");
        assertThat(o.totals().merchandise().amount()).isEqualByComparingTo("88.00");
        assertThat(o.notes()).hasSize(1);
        ResponseEntity<Order> again = webhook.orderCreated(body, null, "wh-2", "orders/create", TENANT, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().id()).isEqualTo(o.id());
    }

    @Test
    void enforcesHmacWhenSecretConfigured() {
        config.set("shopify.webhook_secret", Json.write("s3cret"));
        try {
            String body = ShopifyOrderMapperTest.PAYLOAD.replace("5551234", String.valueOf(System.nanoTime()));
            assertThatThrownBy(() -> webhook.orderCreated(body, null, "wh-3", "orders/create", TENANT, null)).isInstanceOf(UnauthorizedException.class);
            assertThatThrownBy(() -> webhook.orderCreated(body, "bad", "wh-3", "orders/create", TENANT, null)).isInstanceOf(UnauthorizedException.class);
            ResponseEntity<Order> ok = webhook.orderCreated(body, ShopifyWebhookController.hmac("s3cret", body), "wh-3", "orders/create", TENANT, null);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        } finally {
            config.delete("shopify.webhook_secret");
        }
    }
}
