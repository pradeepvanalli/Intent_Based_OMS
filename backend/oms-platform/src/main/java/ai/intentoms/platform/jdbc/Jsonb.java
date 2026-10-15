package ai.intentoms.platform.jdbc;

import ai.intentoms.platform.json.Json;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

/** Helpers for binding jsonb parameters with JdbcClient. Usage: {@code .param("payload", Jsonb.of(map))}. */
public final class Jsonb {
    private Jsonb() {}

    public static PGobject of(Object value) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(value instanceof String s ? s : Json.write(value));
            return o;
        } catch (SQLException e) { throw new IllegalArgumentException(e); }
    }
}
