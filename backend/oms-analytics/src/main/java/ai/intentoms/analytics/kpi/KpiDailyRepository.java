package ai.intentoms.analytics.kpi;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Materialised daily KPI points ({@code kpi_daily}, V1250) written by {@link KpiDailyRollup} and read for days outside the live window. */
@Repository
public class KpiDailyRepository {
    private final JdbcClient jdbc;

    public KpiDailyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Row(LocalDate day, String key, double value, double numerator, double denominator) {}

    public List<DayPoint> read(String key, LocalDate from, LocalDate to) {
        var rows = jdbc.sql("select day, numerator, denominator from kpi_daily where tenant_id = :t and key = :k and day >= :from and day <= :to order by day")
            .param("t", TenantContext.require()).param("k", key).param("from", from).param("to", to).query().listOfRows();
        List<DayPoint> out = new ArrayList<>(rows.size());
        for (var r : rows) out.add(new DayPoint(((Date) r.get("day")).toLocalDate(), num(r.get("numerator")), num(r.get("denominator"))));
        return out;
    }

    /** Snapshot values (gauges) by day. */
    public Map<LocalDate, Double> readValues(String key, LocalDate from, LocalDate to) {
        var rows = jdbc.sql("select day, value from kpi_daily where tenant_id = :t and key = :k and day >= :from and day <= :to order by day")
            .param("t", TenantContext.require()).param("k", key).param("from", from).param("to", to).query().listOfRows();
        Map<LocalDate, Double> out = new TreeMap<>();
        for (var r : rows) out.put(((Date) r.get("day")).toLocalDate(), num(r.get("value")));
        return out;
    }

    public int upsert(List<Row> rows) {
        String t = TenantContext.require();
        int n = 0;
        for (Row r : rows) {
            n += jdbc.sql("""
                insert into kpi_daily(tenant_id, day, key, value, numerator, denominator, computed_at) values (:t, :d, :k, :v, :n, :den, now())
                on conflict (tenant_id, day, key) do update set value = excluded.value, numerator = excluded.numerator, denominator = excluded.denominator, computed_at = now()
                """).param("t", t).param("d", r.day()).param("k", r.key()).param("v", r.value()).param("n", r.numerator()).param("den", r.denominator()).update();
        }
        return n;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
}
