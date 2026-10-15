package ai.intentoms.integrations.address;

import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.AddressVerification;
import ai.intentoms.platform.observability.Metrics;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Section 11 Address validation (Smarty): request fields, dpv_match_code / rdi / coordinates / ZIP+4 mapping, and fail-open on provider errors. */
class SmartyAddressVerifierTest {
    static WireMockServer wm;
    SmartyAddressVerifier verifier;

    static final String CONFIRMED = """
        [{"input_index":0,"candidate_index":0,"delivery_line_1":"233 S Wacker Dr Ste 4400","last_line":"Chicago IL 60606-6306",
          "components":{"primary_number":"233","street_name":"Wacker","zipcode":"60606","plus4_code":"6306","state_abbreviation":"IL","city_name":"Chicago"},
          "metadata":{"record_type":"H","zip_type":"Standard","county_name":"Cook","latitude":41.87878,"longitude":-87.63588,"precision":"Zip9","rdi":"Commercial"},
          "analysis":{"dpv_match_code":"Y","dpv_footnotes":"AABB","dpv_cmra":"N","dpv_vacant":"N","active":"Y","footnotes":"N#"}}]
        """;

    @BeforeAll static void start() { wm = new WireMockServer(WireMockConfiguration.options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        verifier = new SmartyAddressVerifier(new IntegrationsProperties.Smarty("auth-1", "tok-1", wm.baseUrl(), Duration.ofMillis(1500)), RestClient.builder(), new Metrics(new SimpleMeterRegistry()));
    }

    static CaptureRequest.AddressInput chicago(String line2) {
        return new CaptureRequest.AddressInput("ship_to", "Priya N", "233 S Wacker Dr", line2, "Chicago", "IL", "60606", "US", null, null);
    }

    @Test
    void confirmedAddressMapsToVerifiedWithTypeCoordinatesAndZip4() {
        wm.stubFor(get(urlPathEqualTo("/street-address")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(CONFIRMED)));
        AddressVerification v = verifier.verify(chicago("Suite 4400"));
        assertThat(v.status()).isEqualTo("verified");
        assertThat(v.verified()).isTrue();
        assertThat(v.addressType()).isEqualTo("commercial");
        assertThat(v.lat()).isEqualTo(41.87878);
        assertThat(v.lon()).isEqualTo(-87.63588);
        assertThat(v.messages()).anySatisfy(m -> assertThat(m).isEqualTo("ZIP 60606-6306")).anySatisfy(m -> assertThat(m).contains("233 S Wacker Dr Ste 4400"));
        wm.verify(getRequestedFor(urlPathEqualTo("/street-address"))
            .withQueryParam("auth-id", equalTo("auth-1")).withQueryParam("auth-token", equalTo("tok-1")).withQueryParam("candidates", equalTo("1"))
            .withQueryParam("street", equalTo("233 S Wacker Dr")).withQueryParam("street2", equalTo("Suite 4400")).withQueryParam("city", equalTo("Chicago"))
            .withQueryParam("state", equalTo("IL")).withQueryParam("zipcode", equalTo("60606")).withQueryParam("addressee", equalTo("Priya N")));
    }

    @Test
    void dpvCodesMapToStatuses() {
        wm.stubFor(get(urlPathEqualTo("/street-address")).willReturn(aResponse().withStatus(200).withBody(CONFIRMED.replace("\"dpv_match_code\":\"Y\"", "\"dpv_match_code\":\"N\"").replace("\"rdi\":\"Commercial\"", "\"rdi\":\"Residential\""))));
        AddressVerification n = verifier.verify(chicago(null));
        assertThat(n.status()).isEqualTo("undeliverable");
        assertThat(n.deliverable()).isFalse();
        assertThat(n.addressType()).isEqualTo("residential");
        assertThat(n.messages()).anySatisfy(m -> assertThat(m).contains("not deliverable"));

        wm.stubFor(get(urlPathEqualTo("/street-address")).willReturn(aResponse().withStatus(200).withBody(CONFIRMED.replace("\"dpv_match_code\":\"Y\"", "\"dpv_match_code\":\"S\""))));
        AddressVerification s = verifier.verify(chicago("Apt 9Z"));
        assertThat(s.status()).isEqualTo("verified");
        assertThat(s.messages()).anySatisfy(m -> assertThat(m).contains("dropped"));

        wm.stubFor(get(urlPathEqualTo("/street-address")).willReturn(aResponse().withStatus(200).withBody(CONFIRMED.replace("\"record_type\":\"H\"", "\"record_type\":\"P\""))));
        assertThat(verifier.verify(chicago(null)).addressType()).isEqualTo("po_box");

        wm.stubFor(get(urlPathEqualTo("/street-address")).willReturn(aResponse().withStatus(200).withBody("[]")));
        AddressVerification none = verifier.verify(chicago(null));
        assertThat(none.status()).isEqualTo("invalid");
        assertThat(none.deliverable()).isFalse();
    }

    @Test
    void providerFailuresNeverBlockCapture() {
        wm.stubFor(get(urlPathEqualTo("/street-address")).willReturn(aResponse().withStatus(500).withBody("boom")));
        AddressVerification err = verifier.verify(chicago(null));
        assertThat(err.status()).isEqualTo("unverified");
        assertThat(err.deliverable()).isTrue();
        assertThat(err.messages()).anySatisfy(m -> assertThat(m).contains("unavailable"));

        wm.stubFor(get(urlPathEqualTo("/street-address")).willReturn(aResponse().withStatus(200).withFixedDelay(2500).withBody(CONFIRMED)));
        long started = System.nanoTime();
        AddressVerification slow = verifier.verify(chicago(null));
        assertThat(slow.status()).isEqualTo("unverified");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2400));

        wm.resetAll();
        AddressVerification ca = verifier.verify(new CaptureRequest.AddressInput("ship_to", "A", "1 Front St", null, "Toronto", "ON", "M5J", "CA", null, null));
        assertThat(ca.status()).isEqualTo("unverified");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/street-address")));

        SmartyAddressVerifier unconfigured = new SmartyAddressVerifier(new IntegrationsProperties.Smarty(null, null, wm.baseUrl(), Duration.ofSeconds(1)), RestClient.builder(), new Metrics(new SimpleMeterRegistry()));
        assertThat(unconfigured.verify(chicago(null)).status()).isEqualTo("unverified");
    }
}
