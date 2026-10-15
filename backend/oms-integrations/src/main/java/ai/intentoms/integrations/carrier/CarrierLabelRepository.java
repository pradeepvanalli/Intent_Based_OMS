package ai.intentoms.integrations.carrier;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** {@code carrier_label}: label URL, rate and provider ids of a shipment bought through the aggregator (the SPI only carries the tracking code). */
@Repository
public class CarrierLabelRepository {
    public record Label(String id, String shipmentId, String orderId, String carrier, String service, String tracking, String labelUrl, BigDecimal rateUsd,
                        String provider, String providerShipmentId, String providerRateId, Instant createdAt) {}

    private static final RowMapper<Label> MAPPER = (rs, i) -> new Label(rs.getString("id"), rs.getString("shipment_id"), rs.getString("order_id"), rs.getString("carrier"),
        rs.getString("service"), rs.getString("tracking"), rs.getString("label_url"), rs.getBigDecimal("rate_usd"), rs.getString("provider"), rs.getString("provider_shipment_id"),
        rs.getString("provider_rate_id"), rs.getTimestamp("created_at").toInstant());
    private final JdbcClient jdbc;

    public CarrierLabelRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Label upsert(String shipmentId, String orderId, String carrier, String service, String tracking, String labelUrl, BigDecimal rateUsd, String provider,
                        String providerShipmentId, String providerRateId, Instant at) {
        String id = Ids.prefixed("lbl");
        jdbc.sql("""
            insert into carrier_label(id, tenant_id, shipment_id, order_id, carrier, service, tracking, label_url, rate_usd, provider, provider_shipment_id, provider_rate_id, created_at)
            values (:id, :t, :s, :o, :c, :svc, :trk, :url, :rate, :p, :psid, :prid, :at)
            on conflict (tenant_id, shipment_id) do update set carrier = excluded.carrier, service = excluded.service, tracking = excluded.tracking, label_url = excluded.label_url,
              rate_usd = excluded.rate_usd, provider_shipment_id = excluded.provider_shipment_id, provider_rate_id = excluded.provider_rate_id
            """).param("id", id).param("t", TenantContext.require()).param("s", shipmentId).param("o", orderId).param("c", carrier).param("svc", service).param("trk", tracking)
            .param("url", labelUrl).param("rate", rateUsd).param("p", provider).param("psid", providerShipmentId).param("prid", providerRateId).param("at", Timestamp.from(at)).update();
        return forShipment(shipmentId).orElseThrow();
    }

    public Optional<Label> forShipment(String shipmentId) {
        return jdbc.sql("select * from carrier_label where tenant_id = :t and shipment_id = :s").param("t", TenantContext.require()).param("s", shipmentId).query(MAPPER).optional();
    }
}
