package ai.intentoms.order.tracking;

import ai.intentoms.order.OrderTestBase;
import ai.intentoms.order.api.TrackingController;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public tracking link: minted by an operator, resolved without any session or tenant header, customer-safe content only. */
class TrackingIntegrationTest extends OrderTestBase {
    @Autowired TrackingController controller;
    @Autowired TrackingService tracking;
    @Autowired TrackingTokenService tokens;
    @Autowired OrderService orders;

    private static MockHttpServletRequest request(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/public/track/x");
        req.setScheme("https"); req.setServerName("shop.example.com"); req.setServerPort(443); req.setRemoteAddr(ip);
        return req;
    }

    @Test
    void mintsLinkAndResolvesItWithoutSessionOrTenantHeader() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId(), "birthday gift, send everything together"), null);
        TrackingController.TrackingLink link = controller.link(o.id(), request("10.0.0.1"));
        assertThat(link.url()).isEqualTo("https://shop.example.com/track/" + link.token());
        assertThat(link.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));

        // The public endpoint runs with no principal and no tenant bound: the token is the only source of both.
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        TrackingView v = controller.track(link.token(), request("10.0.0.2"));
        assertThat(TenantContext.current()).as("tenant cleared again after the read").isEmpty();

        assertThat(v.orderNumber()).isEqualTo(o.channelOrderId());
        assertThat(v.stage()).isEqualTo("placed");
        assertThat(v.statusLabel()).isNotBlank();
        assertThat(v.placedAt()).isEqualTo(o.placedAt());
        assertThat(v.items()).extracting(TrackingView.Item::qty).containsExactly(2, 1);
        assertThat(v.items()).allSatisfy(i -> { assertThat(i.name()).isNotBlank(); assertThat(i.status()).isNotBlank(); });
        assertThat(v.narrative()).contains(o.channelOrderId());
        assertThat(v.nextStep()).isNotBlank();
        assertThat(v.expiresAt()).isEqualTo(link.expiresAt());
        assertThat(v.pickup()).isFalse();

        String json = Json.write(v);
        for (String forbidden : new String[]{"margin", "cogs", "cost", "nodeId", "node_id", "DC-", "decision", "confidence", "hold", "risk", o.id()}) {
            assertThat(json).as("customer view must not leak %s", forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    void invalidTamperedAndExpiredTokensReadAsNotFound() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
        String good = controller.link(o.id(), request("10.0.0.1")).token();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        assertThatThrownBy(() -> controller.track("nonsense", request("10.0.0.3"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.track(good.substring(0, good.length() - 2) + "zz", request("10.0.0.3"))).isInstanceOf(NotFoundException.class);
        // A token for an order that does not exist in that tenant is equally opaque.
        String ghost = tokens.issue(TENANT, "ord_does_not_exist", Instant.now()).token();
        assertThatThrownBy(() -> controller.track(ghost, request("10.0.0.3"))).isInstanceOf(NotFoundException.class);
        // Expired tokens are refused even though the signature is valid.
        TrackingTokenService.Issued old = tokens.issue(TENANT, o.id(), Instant.now().minus(Duration.ofDays(31)));
        assertThat(tokens.verify(old.token(), Instant.now())).isEmpty();
        assertThatThrownBy(() -> controller.track(old.token(), request("10.0.0.3"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void pickupOrdersShowTheStoreAndTheRightStage() {
        var base = webOrder("W-" + Ids.shortId());
        var pickupAddr = new ai.intentoms.order.capture.CaptureRequest.AddressInput("pickup", "Jane Doe", "ST-AUS-03", null, "Austin", "TX", "78758", "US", null, "commercial");
        // Pickup is detected from the ship method / pickup address; the order type stays standard_ship so the test workflow applies.
        var req = new ai.intentoms.order.capture.CaptureRequest(base.channel(), base.channelOrderId(), base.orderType(), base.customerRef(), base.customer(), base.lines(),
            java.util.List.of(pickupAddr, shipTo("Store pickup ST-AUS-03", "Austin", "TX", "78758")), base.charges(), base.tenders(), base.notes(), java.util.List.of("pickup_consolidated"), "pickup", null, "USD", base.extensions(), null);
        Order o = orders.capture(req, null);
        TrackingView v = tracking.track(tracking.issue(o.id()).token());
        assertThat(v.pickup()).isTrue();
        assertThat(v.pickupLocation()).isNotNull();
        assertThat(v.pickupLocation().storeName()).isEqualTo("Austin store");
        assertThat(v.pickupLocation().city()).isEqualTo("Austin");
        assertThat(v.narrative()).contains("pickup");
        assertThat(Json.write(v)).doesNotContain("ST-AUS-03");
    }
}
