package ai.intentoms.integrations.address;

import ai.intentoms.integrations.config.IntegrationsConfig;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.AddressVerification;
import ai.intentoms.order.validation.AddressVerifier;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Smarty US Street API {@link AddressVerifier} (FR-CAP-05; section 11 Address validation P0: USPS / Smarty). Active when
 * {@code oms.integrations.address.provider=smarty}: {@code GET {baseUrl}/street-address?auth-id&auth-token&candidates=1
 * &match=invalid&street&street2&city&state&zipcode&addressee} with a 1.5 s budget. Non-US addresses are returned
 * {@code unverified} without a call (Smarty's international API is a different product). Any error — timeout, 4xx/5xx,
 * malformed body, missing credentials — yields {@code unverified} with the reason as a message: the provider must never
 * block capture (GR-07 spirit: degraded mode keeps the pipeline moving; the {@code address_risk} decision sees the status).
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "oms.integrations.address", name = "provider", havingValue = "smarty")
public class SmartyAddressVerifier implements AddressVerifier {
    private static final Logger log = LoggerFactory.getLogger(SmartyAddressVerifier.class);
    private static final TypeReference<List<Map<String, Object>>> CANDIDATES = new TypeReference<>() {};
    private final IntegrationsProperties.Smarty props;
    private final RestClient client;
    private final Metrics metrics;

    @Autowired
    public SmartyAddressVerifier(IntegrationsProperties props, RestClient.Builder builder, Metrics metrics) {
        this(props.address().smarty(), builder, metrics);
    }

    SmartyAddressVerifier(IntegrationsProperties.Smarty props, RestClient.Builder builder, Metrics metrics) {
        this.props = props;
        this.client = IntegrationsConfig.client(builder, props.baseUrl(), props.timeout());
        this.metrics = metrics;
        if (!props.configured()) log.warn("Smarty address verifier selected without auth-id/auth-token: every address will be reported unverified");
    }

    @Override
    public AddressVerification verify(CaptureRequest.AddressInput a) {
        String country = a.country() == null || a.country().isBlank() ? "US" : a.country().trim().toUpperCase(Locale.ROOT);
        if (!"US".equals(country) && !"USA".equals(country)) return new AddressVerification("unverified", a.addressType(), null, null, List.of("Verification not available for " + country));
        if (!props.configured()) return new AddressVerification("unverified", a.addressType(), null, null, List.of("Address verification provider not configured"));
        long started = System.nanoTime();
        try {
            String body = client.get().uri(b -> b.path("/street-address")
                    .queryParam("auth-id", props.authId()).queryParam("auth-token", props.authToken())
                    .queryParam("candidates", 1).queryParam("match", "invalid")
                    .queryParamIfPresent("street", opt(a.line1())).queryParamIfPresent("street2", opt(a.line2()))
                    .queryParamIfPresent("city", opt(a.city())).queryParamIfPresent("state", opt(a.state()))
                    .queryParamIfPresent("zipcode", opt(a.zip())).queryParamIfPresent("addressee", opt(a.name()))
                    .build())
                .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            List<Map<String, Object>> candidates = body == null || body.isBlank() ? List.of() : Json.read(body, CANDIDATES);
            AddressVerification v = SmartyResponseMapper.map(candidates, a);
            metrics.count("oms.integrations.address.verified", "provider", "smarty", "status", v.status());
            metrics.record("oms.integrations.address.latency", java.time.Duration.ofNanos(System.nanoTime() - started), "provider", "smarty");
            return v;
        } catch (RuntimeException e) {
            metrics.count("oms.integrations.address.verified", "provider", "smarty", "status", "error");
            log.warn("Smarty verification failed ({}): address passes as unverified", e.getMessage());
            return new AddressVerification("unverified", a.addressType(), null, null, List.of("Address verification unavailable: " + e.getClass().getSimpleName()));
        }
    }

    private static java.util.Optional<String> opt(String s) { return s == null || s.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(s.trim()); }
}
