package ai.intentoms.integrations.carrier;

import ai.intentoms.integrations.config.IntegrationsConfig;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.orchestration.integration.CarrierConnector;
import ai.intentoms.orchestration.shipment.ShipmentRecord;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.model.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * EasyPost {@link CarrierConnector} (section 11 Carriers P0: aggregator for rates, labels, tracking; UC-05). Active when
 * {@code oms.integrations.carrier.provider=easypost}. For a shipment without a tracking number it
 * <ol>
 *   <li>{@code POST /v2/shipments} with {@code to_address} (the order's ship-to), {@code from_address} (the node), one
 *       {@code parcel} (weight from the lines' {@code weight_kg}, ounces) and {@code reference = <shipment id>};</li>
 *   <li>picks the cheapest returned rate whose service matches the requested OMS service ({@code ground} → Ground/GroundAdvantage/
 *       SmartPost-class, {@code air_2day} → 2nd-day class, {@code air_next_day} → next-day class), else the cheapest rate overall;</li>
 *   <li>{@code POST /v2/shipments/{id}/buy} with that rate and returns {@code tracking_code}; the label URL
 *       ({@code postage_label.label_url}), rate and EasyPost ids go to {@code carrier_label} (exposed at
 *       {@code GET /api/v1/shipments/{id}/label}) because the SPI only returns the tracking string.</li>
 * </ol>
 * Delivery scans come back through {@code POST /webhooks/easypost} ({@link EasyPostWebhookController}). Failures raise a
 * {@code ConflictException} (details.code {@code carrier_label_failed}) so the workflow activity retries with backoff. Orders are
 * read through {@code OrderRepository} because {@code OrderService} depends on {@code ShipmentService}, which depends on this bean.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "oms.integrations.carrier", name = "provider", havingValue = "easypost")
public class EasyPostCarrierConnector implements CarrierConnector {
    private static final Logger log = LoggerFactory.getLogger(EasyPostCarrierConnector.class);
    public static final String PROVIDER = "easypost";
    private final IntegrationsProperties.EasyPost props;
    private final RestClient client;
    private final OrderRepository orders;
    private final NodeRegistry nodes;
    private final CarrierLabelRepository labels;
    private final TimeSource time;
    private final Metrics metrics;

    public EasyPostCarrierConnector(IntegrationsProperties props, RestClient.Builder builder, OrderRepository orders, NodeRegistry nodes, CarrierLabelRepository labels, TimeSource time, Metrics metrics) {
        this.props = props.carrier().easypost();
        this.client = IntegrationsConfig.client(builder, this.props.baseUrl(), this.props.timeout());
        this.orders = orders;
        this.nodes = nodes;
        this.labels = labels;
        this.time = time;
        this.metrics = metrics;
        if (!this.props.configured()) log.warn("EasyPost carrier connector selected without an API key: label purchases will fail until oms.integrations.carrier.easypost.api-key is set");
    }

    @Override
    public String createLabel(ShipmentRecord shipment) {
        if (!props.configured()) throw new ConflictException("Carrier connector is not configured", Map.of("code", "carrier_not_configured", "provider", PROVIDER));
        Optional<CarrierLabelRepository.Label> existing = labels.forShipment(shipment.id());
        if (existing.isPresent() && existing.get().tracking() != null) return existing.get().tracking();
        Order order = orders.get(shipment.orderId());
        Map<String, Object> created = post("/v2/shipments", Map.of("shipment", shipmentRequest(order, shipment)));
        String epShipmentId = str(created.get("id"));
        Map<String, Object> rate = pickRate(rates(created), shipment.service());
        if (rate == null) throw new ConflictException("Carrier returned no rates for shipment " + shipment.id(), Map.of("code", "carrier_no_rates", "provider", PROVIDER, "service", nz(shipment.service())));
        Map<String, Object> bought = post("/v2/shipments/" + epShipmentId + "/buy", Map.of("rate", Map.of("id", rate.get("id"))));
        String tracking = str(bought.get("tracking_code"));
        if (tracking == null) throw new ConflictException("Carrier label purchase returned no tracking code", Map.of("code", "carrier_label_failed", "provider", PROVIDER));
        Map<String, Object> label = sub(bought, "postage_label");
        Map<String, Object> boughtRate = sub(bought, "selected_rate").isEmpty() ? rate : sub(bought, "selected_rate");
        labels.upsert(shipment.id(), shipment.orderId(), str(boughtRate.get("carrier")), str(boughtRate.get("service")), tracking, str(label.get("label_url")),
            decimal(boughtRate.get("rate")), PROVIDER, epShipmentId, str(boughtRate.get("id")), time.now());
        metrics.count("oms.integrations.carrier.label", "provider", PROVIDER, "carrier", nz(str(boughtRate.get("carrier"))));
        log.info("EasyPost label for shipment {} ({} {}): tracking {}, rate {} USD", shipment.id(), boughtRate.get("carrier"), boughtRate.get("service"), tracking, boughtRate.get("rate"));
        return tracking;
    }

    /** EasyPost shipment body: addresses, parcel and options; pure so it is unit-testable. */
    Map<String, Object> shipmentRequest(Order order, ShipmentRecord shipment) {
        Map<String, Object> to = order.shipTo().map(EasyPostCarrierConnector::address).orElseThrow(() -> new ConflictException("Order " + order.id() + " has no ship-to address", Map.of("code", "carrier_no_ship_to")));
        Map<String, Object> from = nodes.find(shipment.nodeId()).map(EasyPostCarrierConnector::address).orElseGet(() -> Map.of("name", shipment.nodeId(), "country", "US"));
        double kg = 0;
        for (ShipmentRecord.Line l : shipment.lines()) {
            double w = order.line(l.lineId()).map(OrderLine::weightKg).orElse(0.5);
            kg += w * l.qty();
        }
        double ounces = Math.max(1.0, Math.round(kg * 35.274 * 10) / 10.0);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("to_address", to);
        req.put("from_address", from);
        req.put("parcel", Map.of("weight", ounces));
        req.put("reference", shipment.id());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("label_format", "PDF");
        if (order.hasHazmat()) options.put("hazmat", "LIMITED_QUANTITY");
        req.put("options", options);
        return req;
    }

    static Map<String, Object> address(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "name", a.name()); put(m, "street1", a.line1()); put(m, "street2", a.line2()); put(m, "city", a.city()); put(m, "state", a.state());
        put(m, "zip", a.zip()); put(m, "country", a.country() == null ? "US" : a.country()); put(m, "phone", a.phone());
        if ("commercial".equalsIgnoreCase(a.addressType())) m.put("residential", false); else if ("residential".equalsIgnoreCase(a.addressType())) m.put("residential", true);
        return m;
    }

    /** Nodes carry state / ZIP / country only (the carrier account holds the full origin address per location). */
    static Map<String, Object> address(Node n) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "name", n.name() == null ? n.id() : n.name());
        put(m, "company", n.id());
        put(m, "state", n.state());
        put(m, "zip", n.zip());
        m.put("country", n.country() == null ? "US" : n.country());
        return m;
    }

    /** Cheapest rate in the service class the OMS asked for; cheapest overall when nothing matches. */
    static Map<String, Object> pickRate(List<Map<String, Object>> rates, String service) {
        if (rates == null || rates.isEmpty()) return null;
        List<Map<String, Object>> matching = new ArrayList<>();
        String wanted = service == null ? "ground" : service.toLowerCase(Locale.ROOT);
        for (Map<String, Object> r : rates) if (serviceClass(str(r.get("service"))).equals(wanted)) matching.add(r);
        List<Map<String, Object>> pool = matching.isEmpty() ? rates : matching;
        Map<String, Object> best = null;
        BigDecimal bestRate = null;
        for (Map<String, Object> r : pool) {
            BigDecimal rate = decimal(r.get("rate"));
            if (rate == null) continue;
            if (best == null || rate.compareTo(bestRate) < 0) { best = r; bestRate = rate; }
        }
        return best == null ? pool.get(0) : best;
    }

    /** Carrier service name → OMS ship method class. */
    static String serviceClass(String carrierService) {
        String s = carrierService == null ? "" : carrierService.toLowerCase(Locale.ROOT);
        if (s.contains("next") || s.contains("overnight") || s.contains("priority_overnight") || s.contains("1day") || s.contains("express") && !s.contains("saver")) return "air_next_day";
        if (s.contains("2day") || s.contains("2nd") || s.contains("second") || s.contains("2_day") || s.contains("twoday")) return "air_2day";
        return "ground";
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rates(Map<String, Object> shipment) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (shipment.get("rates") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            String res = client.post().uri(path).headers(h -> h.setBasicAuth(props.apiKey(), "")).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class);
            return Json.readMap(res);
        } catch (RestClientResponseException e) {
            metrics.count("oms.integrations.carrier.error", "provider", PROVIDER, "status", String.valueOf(e.getStatusCode().value()));
            throw new ConflictException("Carrier API rejected the request (HTTP " + e.getStatusCode().value() + ")", Map.of("code", "carrier_label_failed", "provider", PROVIDER, "status", e.getStatusCode().value(), "body", abbreviate(e.getResponseBodyAsString())));
        } catch (RuntimeException e) {
            metrics.count("oms.integrations.carrier.error", "provider", PROVIDER, "status", "io");
            throw new ConflictException("Carrier API call failed: " + e.getClass().getSimpleName(), Map.of("code", "carrier_label_failed", "provider", PROVIDER, "reason", nz(e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String k) { return m.get(k) instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(); }
    static BigDecimal decimal(Object o) { try { return o == null ? null : new BigDecimal(String.valueOf(o)); } catch (NumberFormatException e) { return null; } }
    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static String nz(String s) { return s == null ? "" : s; }
    static String abbreviate(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s; }
    private static void put(Map<String, Object> m, String k, Object v) { if (v != null) m.put(k, v); }
}
