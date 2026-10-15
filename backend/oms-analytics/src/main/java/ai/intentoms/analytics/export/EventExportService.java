package ai.intentoms.analytics.export;

import ai.intentoms.analytics.config.AnalyticsProperties;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Event export for warehouse loaders (FR-ANL-03, P1 minimal): streams the tenant's ledger events in a time window as
 * newline-delimited CloudEvents 1.0 JSON, in global ledger order, fetched in keyset batches so memory stays flat.
 * Snowflake, BigQuery and Databricks all ingest NDJSON natively.
 */
@Service
public class EventExportService {
    private final JdbcClient jdbc;
    private final AnalyticsProperties props;

    public EventExportService(JdbcClient jdbc, AnalyticsProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Write one CloudEvent per line for events with {@code from <= occurred_at < to}; {@code typePrefixes} (optional) restricts event types. Returns lines written. */
    public long write(Instant from, Instant to, List<String> typePrefixes, OutputStream out) {
        String tenant = TenantContext.require();
        List<String> prefixes = typePrefixes == null ? List.of() : typePrefixes.stream().filter(p -> p != null && !p.isBlank()).toList();
        long written = 0;
        long after = 0;
        try {
            while (true) {
                List<Map<String, Object>> rows = batch(tenant, from, to, prefixes, after);
                if (rows.isEmpty()) break;
                for (Map<String, Object> r : rows) {
                    after = ((Number) r.get("position")).longValue();
                    out.write(Json.write(cloudEvent(r)).getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    written++;
                }
                out.flush();
                if (rows.size() < props.exportBatch()) break;
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written;
    }

    private List<Map<String, Object>> batch(String tenant, Instant from, Instant to, List<String> prefixes, long afterPosition) {
        StringBuilder sql = new StringBuilder("""
            select position, event_id, tenant_id, aggregate_type, aggregate_id, sequence, type, payload::text as payload, actor, causation_id, correlation_id, metadata::text as metadata, occurred_at
            from event where tenant_id = :t and occurred_at >= :from and occurred_at < :to and position > :after
            """);
        List<String> clauses = new ArrayList<>();
        for (int i = 0; i < prefixes.size(); i++) clauses.add("type like :p" + i);
        if (!clauses.isEmpty()) sql.append(" and (").append(String.join(" or ", clauses)).append(")");
        sql.append(" order by position limit :limit");
        var spec = jdbc.sql(sql.toString()).param("t", tenant).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("after", afterPosition).param("limit", props.exportBatch());
        for (int i = 0; i < prefixes.size(); i++) spec = spec.param("p" + i, prefixes.get(i).replace("%", "") + "%");
        return spec.query().listOfRows();
    }

    /** CloudEvents 1.0 envelope, identical to the outbox representation ({@code Event.toCloudEvent}) plus the ledger position for incremental loads. */
    static Map<String, Object> cloudEvent(Map<String, Object> r) {
        Map<String, Object> ce = new LinkedHashMap<>();
        String tenant = (String) r.get("tenant_id"), aggType = (String) r.get("aggregate_type"), aggId = (String) r.get("aggregate_id");
        ce.put("specversion", "1.0");
        ce.put("id", r.get("event_id"));
        ce.put("source", "/tenants/" + tenant + "/" + aggType + "/" + aggId);
        ce.put("type", "ai.intentoms." + r.get("type"));
        ce.put("subject", aggId);
        ce.put("time", ((Timestamp) r.get("occurred_at")).toInstant().toString());
        ce.put("datacontenttype", "application/json");
        ce.put("tenantid", tenant);
        ce.put("sequence", ((Number) r.get("sequence")).longValue());
        ce.put("position", ((Number) r.get("position")).longValue());
        ce.put("actor", r.get("actor"));
        if (r.get("causation_id") != null) ce.put("causationid", r.get("causation_id"));
        if (r.get("correlation_id") != null) ce.put("correlationid", r.get("correlation_id"));
        Map<String, Object> meta = Json.readMap((String) r.get("metadata"));
        if (!meta.isEmpty()) ce.put("metadata", meta);
        ce.put("data", Json.readMap((String) r.get("payload")));
        return ce;
    }
}
