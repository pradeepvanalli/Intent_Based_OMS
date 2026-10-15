package ai.intentoms.monitoring.alert;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of alert episodes (FR-MON-06). */
@Repository
public class AlertRepository {
    private static final TypeReference<Map<String, String>> LINKS = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final String COLS = "id, key, severity, title, body, links::text as links, first_seen, last_seen, count, channels_sent::text as channels_sent";
    private static final RowMapper<AlertRecord> MAPPER = (rs, i) -> new AlertRecord(rs.getString("id"), rs.getString("key"), rs.getString("severity"), rs.getString("title"),
        rs.getString("body"), Json.read(rs.getString("links"), LINKS), rs.getTimestamp("first_seen").toInstant(), rs.getTimestamp("last_seen").toInstant(), rs.getInt("count"),
        Json.read(rs.getString("channels_sent"), STRINGS));
    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<AlertRecord> latestByKey(String key) {
        return jdbc.sql("select " + COLS + " from alert where tenant_id = :t and key = :k order by last_seen desc limit 1").param("t", TenantContext.require()).param("k", key).query(MAPPER).optional();
    }

    public AlertRecord insert(Alert a, Instant now, List<String> channelsSent) {
        String id = Ids.prefixed("alt");
        jdbc.sql("insert into alert(id, tenant_id, key, severity, title, body, links, first_seen, last_seen, count, channels_sent) values (:id, :t, :k, :s, :title, :body, :links, :now, :now, 1, :ch)")
            .param("id", id).param("t", TenantContext.require()).param("k", a.key()).param("s", a.severity()).param("title", a.title()).param("body", a.body())
            .param("links", Jsonb.of(a.links())).param("now", Timestamp.from(now)).param("ch", Jsonb.of(channelsSent)).update();
        return get(id);
    }

    public AlertRecord touch(String id, Instant now) {
        jdbc.sql("update alert set last_seen = :now, count = count + 1 where tenant_id = :t and id = :id").param("now", Timestamp.from(now)).param("t", TenantContext.require()).param("id", id).update();
        return get(id);
    }

    public AlertRecord get(String id) {
        return jdbc.sql("select " + COLS + " from alert where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).single();
    }

    public Page<AlertRecord> list(String severity, int offset, int limit) {
        String where = " where tenant_id = :t" + (severity == null ? "" : " and severity = :sev");
        var count = jdbc.sql("select count(*) from alert" + where).param("t", TenantContext.require());
        var rows = jdbc.sql("select " + COLS + " from alert" + where + " order by last_seen desc limit :l offset :o").param("t", TenantContext.require()).param("l", limit).param("o", offset);
        if (severity != null) { count = count.param("sev", severity.toLowerCase()); rows = rows.param("sev", severity.toLowerCase()); }
        return Page.of(rows.query(MAPPER).list(), count.query(Long.class).single(), offset, limit);
    }
}
