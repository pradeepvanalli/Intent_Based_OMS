package ai.intentoms.integrations.marketplace;

import ai.intentoms.integrations.config.IntegrationsConfig;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.platform.json.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Amazon Selling Partner API client for order ingestion (FR-CAP-01; section 11 marketplaces), written against
 * the public API shape and exercised only by {@link MarketplacePoller} when {@code oms.integrations.marketplace.amazon.enabled=true}:
 * <ul>
 *   <li>LWA token refresh: {@code POST {lwaUrl}} form {@code grant_type=refresh_token&refresh_token&client_id&client_secret}
 *       → {@code {access_token, expires_in}}; cached until 60 s before expiry.</li>
 *   <li>{@code GET /orders/v0/orders?MarketplaceIds=…&CreatedAfter=…&OrderStatuses=Unshipped,PartiallyShipped[&NextToken=…]}
 *       with header {@code x-amz-access-token} → {@code payload.Orders[]} + {@code payload.NextToken}.</li>
 *   <li>{@code GET /orders/v0/orders/{AmazonOrderId}/orderItems} → {@code payload.OrderItems[]}, inlined on the order so
 *       {@link MarketplaceOrderMapper#mapAmazon} sees one document.</li>
 * </ul>
 * SP-API rate limits (0.0167 rps on getOrders) are respected by the poll interval; a 429 ends the current poll early and
 * the watermark is not advanced so the next poll resumes.
 */
@Component
public class AmazonSpApiClient {
    private static final Logger log = LoggerFactory.getLogger(AmazonSpApiClient.class);
    private final IntegrationsProperties.Amazon props;
    private final RestClient api;
    private final RestClient lwa;
    private volatile String accessToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    @Autowired
    public AmazonSpApiClient(IntegrationsProperties props, RestClient.Builder builder) {
        this(props.marketplace().amazon(), builder);
    }

    AmazonSpApiClient(IntegrationsProperties.Amazon props, RestClient.Builder builder) {
        this.props = props;
        this.api = IntegrationsConfig.client(builder, props.baseUrl(), props.timeout());
        this.lwa = IntegrationsConfig.client(builder, props.lwaUrl(), props.timeout());
    }

    public boolean configured() { return props.credentialsPresent(); }

    /** Access token, refreshed through LWA when missing or about to expire. */
    public synchronized String accessToken(Instant now) {
        if (accessToken != null && now.isBefore(tokenExpiry.minusSeconds(60))) return accessToken;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", props.refreshToken());
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        String body = lwa.post().uri("").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
        Map<String, Object> res = Json.readMap(body);
        Object token = res.get("access_token");
        if (token == null) throw new IllegalStateException("LWA token response had no access_token");
        long expires = res.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        accessToken = String.valueOf(token);
        tokenExpiry = now.plus(Duration.ofSeconds(expires));
        return accessToken;
    }

    /** Orders created after {@code since} that still need fulfilment, each with its {@code OrderItems} inlined. */
    public List<Map<String, Object>> ordersCreatedAfter(Instant since, Instant now) {
        String token = accessToken(now);
        List<Map<String, Object>> out = new ArrayList<>();
        String next = null;
        int pages = 0;
        do {
            String nextToken = next;
            String body = api.get().uri(b -> {
                b.path("/orders/v0/orders");
                if (nextToken != null) b.queryParam("NextToken", nextToken);
                else b.queryParam("MarketplaceIds", String.join(",", props.marketplaceIds())).queryParam("CreatedAfter", since.toString()).queryParam("OrderStatuses", "Unshipped,PartiallyShipped");
                return b.build();
            }).header("x-amz-access-token", token).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            Map<String, Object> payload = payload(body);
            if (payload.get("Orders") instanceof List<?> orders) {
                for (Object o : orders) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Map<String, Object> order = new LinkedHashMap<>(MarketplaceOrderMapper.map(m));
                    order.put("OrderItems", orderItems(String.valueOf(order.get("AmazonOrderId")), token));
                    out.add(order);
                }
            }
            next = payload.get("NextToken") == null ? null : String.valueOf(payload.get("NextToken"));
        } while (next != null && ++pages < 20);
        log.debug("SP-API returned {} orders created after {}", out.size(), since);
        return out;
    }

    public List<Map<String, Object>> orderItems(String amazonOrderId, String token) {
        String body = api.get().uri("/orders/v0/orders/{id}/orderItems", amazonOrderId).header("x-amz-access-token", token).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        Map<String, Object> payload = payload(body);
        List<Map<String, Object>> items = new ArrayList<>();
        if (payload.get("OrderItems") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) items.add(MarketplaceOrderMapper.map(m));
        return items;
    }

    private static Map<String, Object> payload(String body) {
        Map<String, Object> res = Json.readMap(body);
        return res.get("payload") instanceof Map<?, ?> p ? MarketplaceOrderMapper.map(p) : res;
    }
}
