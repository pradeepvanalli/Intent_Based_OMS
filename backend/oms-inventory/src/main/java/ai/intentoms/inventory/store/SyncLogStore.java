package ai.intentoms.inventory.store;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Append-only log of feeds and reconciliation runs (FR-INV-04). */
@Repository
public class SyncLogStore {
    private final JdbcClient jdbc;

    public SyncLogStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Records one run; {@code drift} is null for plain feeds. Returns the log id. */
    public String append(String tenant, String source, int positions, Object drift, Instant at) {
        String id = Ids.id();
        jdbc.sql("insert into inventory_sync_log(id, tenant_id, source, positions, received_at, drift) values (:id, :t, :src, :p, :at, :d)")
            .param("id", id).param("t", tenant).param("src", source).param("p", positions).param("at", Timestamp.from(at))
            .param("d", drift == null ? null : Jsonb.of(drift)).update();
        return id;
    }
}
