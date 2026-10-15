package com.example.oms.extensions;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.AddressVerification;
import ai.intentoms.order.validation.AddressVerifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Example {@link AddressVerifier} backed by a real address service (FR-CAP-05 validation step {@code address}).
 *
 * <p>Plugging in: the order module registers {@code MockAddressVerifier} only when no other {@link AddressVerifier}
 * bean exists, so declaring this {@code @Component} replaces it. The capture pipeline calls {@link #verify} for each
 * address in the request; the result feeds the {@code address} validation step (invalid or undeliverable →
 * {@code 422 validation_failed}) and the {@code address_risk} decision inputs ({@code verification_status},
 * {@code type}).
 *
 * <p>Return statuses: {@code verified} | {@code unverified} (could not check, e.g. unsupported country) |
 * {@code invalid} (format problems) | {@code undeliverable}. {@code addressType}: {@code residential} |
 * {@code commercial} | {@code po_box} when known. Keep this fast (it is on the capture path, NFR-02) and never throw
 * for a normal "not found": return {@code invalid} with messages. Fail open ({@code unverified}) on transport errors
 * so an address vendor outage does not stop order capture.
 */
@Component
public class CustomAddressVerifier implements AddressVerifier {
    private final RestClient http;
    private final String url;
    private final String apiKey;

    public CustomAddressVerifier(RestClient.Builder builder, org.springframework.core.env.Environment env) {
        this.http = builder.build();
        this.url = env.getProperty("example.address.url", "");
        this.apiKey = env.getProperty("example.address.api-key", "");
    }

    @Override
    public AddressVerification verify(CaptureRequest.AddressInput a) {
        if (url.isBlank()) return new AddressVerification("unverified", a.addressType(), null, null, List.of("address verifier not configured"));
        try {
            Map<?, ?> r = http.post().uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of("line1", nz(a.line1()), "line2", nz(a.line2()), "city", nz(a.city()), "state", nz(a.state()),
                             "postalCode", nz(a.zip()), "country", a.country() == null ? "US" : a.country()))
                .retrieve().body(Map.class);
            if (r == null) return new AddressVerification("unverified", a.addressType(), null, null, List.of("empty response"));
            String status = switch (String.valueOf(r.get("deliverability"))) {
                case "deliverable" -> "verified";
                case "undeliverable" -> "undeliverable";
                case "unknown" -> "unverified";
                default -> "invalid";
            };
            String type = switch (String.valueOf(r.get("rdi"))) {       // residential delivery indicator
                case "residential" -> "residential";
                case "commercial" -> "commercial";
                default -> "po_box".equals(r.get("type")) ? "po_box" : a.addressType();
            };
            Double lat = r.get("lat") instanceof Number n ? n.doubleValue() : null;
            Double lon = r.get("lon") instanceof Number n ? n.doubleValue() : null;
            @SuppressWarnings("unchecked") List<String> messages = r.get("messages") instanceof List<?> l ? (List<String>) l : List.of();
            return new AddressVerification(status, type, lat, lon, messages);
        } catch (RuntimeException e) {
            return new AddressVerification("unverified", a.addressType(), null, null, List.of("address service unavailable: " + e.getClass().getSimpleName()));
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
