package ai.intentoms.integrations.wms;

import ai.intentoms.integrations.config.HmacSignatures;
import ai.intentoms.integrations.config.IntegrationsConfig;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.orchestration.integration.WmsConnector;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic HTTP {@link WmsConnector} (section 11 "WMS and 3PL" P0: generic + one; FR-WF-05, UC-03). Active when
 * {@code oms.integrations.wms.provider=http}.
 *
 * <h2>Outbound contract (OMS → WMS)</h2>
 * {@code POST <tenant_config wms.<nodeId>.release_url | wms.release_url>} with JSON
 * <pre>{
 *   "documentType": "release" | "cancel",
 *   "documentId":   "<fulfilmentGroupId>:release" | "<fulfilmentGroupId>:cancel:<n>",   // idempotency key for the WMS
 *   "tenantId":     "...", "orderId": "...", "channelOrderId": "...", "orderType": "standard_ship",
 *   "fulfilmentGroupId": "...", "nodeId": "DC-NJ", "policy": "...", "shipMethod": "ground", "service": "ground",
 *   "priority": 1..5 (1 = highest: next-day/same-day, 3 = 2-day, 5 = ground),
 *   "promiseDate": "2026-10-09", "allocations": [{"lineId": "1", "sku": "HOOD-GRY-M", "qty": 2, "description": "...", "hazmat": false}],
 *   "shipTo": {"name","line1","line2","city","state","zip","country","phone","addressType"},
 *   "customer": {"ref", "name"}, "giftNote"?: "...", "reason"?: "..." (cancel), "sentAt": "..."
 * }</pre>
 * Headers: {@code Content-Type: application/json}, {@code X-Tenant-Id}, {@code X-Oms-Document-Id}, {@code X-Oms-Document-Type},
 * and {@code X-Webhook-Signature: sha256=<hex HMAC-SHA256(wms.webhook_secret, body)>} when the tenant configured a secret.
 * Any 2xx is an acknowledgement (the body, if JSON, is returned to the workflow); 4xx other than 408/429 is a permanent
 * failure; 5xx / 408 / 429 / I/O errors are retried {@code oms.integrations.wms.http.retries} times with linear backoff.
 * Every attempt lands in {@code wms_outbound_log}. A release that was already acknowledged is not re-sent on a workflow
 * replay (the log is the dedup store), matching {@code MockWmsConnector}.
 *
 * <h2>Inbound contract (WMS → OMS)</h2>
 * The WMS reports progress to the existing {@code POST /webhooks/wms/{nodeId}} with
 * {@code {eventId, type shipped|short_pick|store_rejected|pick_started|packed|picked|staged_for_pickup|picked_up, orderId, fulfilmentGroupId, lines[{lineId, qty}], carrier?, service?, tracking?}}
 * (header {@code X-Tenant-Id}; {@code X-Webhook-Signature} when {@code webhooks.inbound_secret} is set).
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "oms.integrations.wms", name = "provider", havingValue = "http")
public class HttpWmsConnector implements WmsConnector {
    private static final Logger log = LoggerFactory.getLogger(HttpWmsConnector.class);
    public static final String RELEASE = "release";
    public static final String CANCEL = "cancel";
    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    private final IntegrationsProperties.Http props;
    private final RestClient client;
    private final TenantConfigStore config;
    private final WmsOutboundLogRepository outbound;
    private final TimeSource time;
    private final Metrics metrics;

    public HttpWmsConnector(IntegrationsProperties props, RestClient.Builder builder, TenantConfigStore config, WmsOutboundLogRepository outbound, TimeSource time, Metrics metrics) {
        this.props = props.wms().http();
        this.client = IntegrationsConfig.client(builder, "", this.props.timeout());
        this.config = config;
        this.outbound = outbound;
        this.time = time;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> sendRelease(Order order, FulfilmentGroup group) {
        String documentId = group.id() + ":" + RELEASE;
        var sent = outbound.forGroup(group.id(), RELEASE).stream().filter(e -> "sent".equals(e.status())).findFirst();
        if (sent.isPresent()) {
            Map<String, Object> ack = new LinkedHashMap<>(Map.of("documentId", documentId, "nodeId", group.nodeId(), "replayed", true));
            ack.put("ackedAt", sent.get().at().toString());
            return ack;
        }
        String url = url(group.nodeId(), IntegrationsConfigKeys.wmsReleaseUrl(group.nodeId()), IntegrationsConfigKeys.WMS_RELEASE_URL);
        Map<String, Object> doc = document(RELEASE, documentId, order, group, null);
        Map<String, Object> ack = post(url, doc, order, group);
        ack.put("replayed", false);
        return ack;
    }

    @Override
    public Map<String, Object> cancelRelease(Order order, FulfilmentGroup group, String reason) {
        int n = outbound.forGroup(group.id(), CANCEL).size() + 1;
        String documentId = group.id() + ":" + CANCEL + ":" + n;
        String url = url(group.nodeId(), IntegrationsConfigKeys.wmsCancelUrl(group.nodeId()), IntegrationsConfigKeys.WMS_CANCEL_URL,
            IntegrationsConfigKeys.wmsReleaseUrl(group.nodeId()), IntegrationsConfigKeys.WMS_RELEASE_URL);
        Map<String, Object> doc = document(CANCEL, documentId, order, group, reason);
        Map<String, Object> ack = post(url, doc, order, group);
        ack.put("cancelled", true);
        ack.put("reason", reason == null ? "" : reason);
        return ack;
    }

    /** The release / cancel document; pure so the contract is unit-testable. */
    public Map<String, Object> document(String type, String documentId, Order order, FulfilmentGroup group, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("documentType", type);
        d.put("documentId", documentId);
        d.put("tenantId", TenantContext.require());
        d.put("orderId", order.id());
        d.put("channelOrderId", order.channelOrderId());
        d.put("channel", order.channel());
        d.put("orderType", order.orderType());
        d.put("fulfilmentGroupId", group.id());
        d.put("nodeId", group.nodeId());
        if (group.policy() != null) d.put("policy", group.policy());
        String service = group.shipMethod() != null ? group.shipMethod() : order.shipMethod() == null ? "ground" : order.shipMethod();
        d.put("shipMethod", service);
        d.put("service", service);
        d.put("priority", priority(service, order));
        if (group.promiseDate() != null) d.put("promiseDate", group.promiseDate().toString());
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (FulfilmentGroup.Allocation a : group.allocations()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineId", a.lineId());
            row.put("sku", a.sku());
            row.put("qty", a.qty());
            order.line(a.lineId()).ifPresent(l -> { if (l.description() != null) row.put("description", l.description()); row.put("hazmat", l.hazmat()); row.put("weightKg", l.weightKg()); });
            allocations.add(row);
        }
        d.put("allocations", allocations);
        order.shipTo().ifPresent(a -> d.put("shipTo", address(a)));
        if (order.customer() != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            if (order.customer().ref() != null) c.put("ref", order.customer().ref());
            if (order.customer().name() != null) c.put("name", order.customer().name());
            d.put("customer", c);
        }
        order.notes().stream().filter(n -> n.customerVisible() && n.text() != null).findFirst().ifPresent(n -> d.put("giftNote", n.text()));
        if (reason != null) d.put("reason", reason);
        d.put("sentAt", time.now().toString());
        return d;
    }

    /** 1 = next/same day, 3 = 2-day, 5 = ground; agent and intent {@code earliest_partial} orders move one step up. */
    static int priority(String service, Order order) {
        String s = service == null ? "" : service.toLowerCase();
        int p = s.contains("next") || s.contains("same") ? 1 : s.contains("2") ? 3 : 5;
        if (order.intent() != null && "earliest_partial".equals(order.intent().policy()) && p > 1) p--;
        return p;
    }

    private Map<String, Object> post(String url, Map<String, Object> doc, Order order, FulfilmentGroup group) {
        String body = Json.write(doc);
        String secret = config.getString(IntegrationsConfigKeys.WMS_WEBHOOK_SECRET, null);
        String type = String.valueOf(doc.get("documentType"));
        String documentId = String.valueOf(doc.get("documentId"));
        int attempts = Math.max(1, props.retries());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ResponseEntity<String> res = client.post().uri(url).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", TenantContext.require()).header("X-Oms-Document-Id", documentId).header("X-Oms-Document-Type", type)
                    .headers(h -> { if (secret != null && !secret.isBlank()) h.set(SIGNATURE_HEADER, "sha256=" + HmacSignatures.hex(secret, body)); })
                    .body(body).retrieve().toEntity(String.class);
                outbound.insert(order.id(), group.id(), group.nodeId(), type, documentId, url, attempt, "sent", res.getStatusCode().value(), abbreviate(res.getBody()), null, time.now());
                metrics.count("oms.integrations.wms.sent", "type", type);
                log.info("WMS {} {} for order {} group {} → {} (attempt {})", type, documentId, order.id(), group.id(), res.getStatusCode().value(), attempt);
                return ack(documentId, group, res);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status >= 500 || status == 408 || status == 429;
                outbound.insert(order.id(), group.id(), group.nodeId(), type, documentId, url, attempt, retryable && attempt < attempts ? "failed" : "gave_up", status, abbreviate(e.getResponseBodyAsString()), "HTTP " + status, time.now());
                last = new ConflictException("WMS rejected " + type + " " + documentId + " (HTTP " + status + ")", Map.of("code", "wms_" + type + "_failed", "status", status, "url", url));
                if (!retryable) break;
            } catch (RuntimeException e) {
                outbound.insert(order.id(), group.id(), group.nodeId(), type, documentId, url, attempt, attempt < attempts ? "failed" : "gave_up", null, null, e.getClass().getSimpleName() + ": " + e.getMessage(), time.now());
                last = new ConflictException("WMS call failed for " + type + " " + documentId + ": " + e.getClass().getSimpleName(), Map.of("code", "wms_" + type + "_failed", "url", url, "reason", String.valueOf(e.getMessage())));
            }
            metrics.count("oms.integrations.wms.failed", "type", type);
            if (attempt < attempts) sleep(props.backoff().toMillis() * attempt);
        }
        throw last;
    }

    private static Map<String, Object> ack(String documentId, FulfilmentGroup group, ResponseEntity<String> res) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("documentId", documentId);
        ack.put("nodeId", group.nodeId());
        ack.put("httpStatus", res.getStatusCode().value());
        String body = res.getBody();
        if (body != null && !body.isBlank()) {
            try { Map<String, Object> json = Json.readMap(body); json.forEach(ack::putIfAbsent); } catch (RuntimeException ignored) { ack.put("response", abbreviate(body)); }
        }
        return ack;
    }

    private String url(String nodeId, String... keys) {
        for (String k : keys) { String v = config.getString(k, null); if (v != null && !v.isBlank()) return v.trim(); }
        throw new ConflictException("No WMS endpoint configured for node " + nodeId, Map.of("code", "wms_not_configured", "keys", List.of(keys)));
    }

    private static Map<String, Object> address(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "name", a.name()); put(m, "line1", a.line1()); put(m, "line2", a.line2()); put(m, "city", a.city()); put(m, "state", a.state());
        put(m, "zip", a.zip()); put(m, "country", a.country()); put(m, "phone", a.phone()); put(m, "addressType", a.addressType());
        return m;
    }

    private static void put(Map<String, Object> m, String k, Object v) { if (v != null) m.put(k, v); }
    private static String abbreviate(String s) { return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) + "…" : s; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
