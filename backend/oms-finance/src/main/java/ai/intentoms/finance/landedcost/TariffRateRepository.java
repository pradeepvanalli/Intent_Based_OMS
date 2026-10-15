package ai.intentoms.finance.landedcost;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Tenant-scoped SQL for {@code tariff_rate}. */
@Repository
public class TariffRateRepository {
    private final JdbcClient jdbc;

    static final RowMapper<TariffRate> MAPPER = (rs, i) -> {
        Timestamp to = rs.getTimestamp("effective_to");
        return new TariffRate(rs.getString("id"), rs.getString("origin_country"), rs.getString("dest_country"), rs.getString("hs_code_prefix"),
            rs.getBigDecimal("duty_rate"), rs.getBigDecimal("brokerage_fee"), rs.getBigDecimal("surcharge_rate"),
            rs.getTimestamp("effective_from").toInstant(), to == null ? null : to.toInstant(), rs.getString("note"));
    };

    public TariffRateRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public TariffRate upsert(TariffRate r) {
        String id = r.id() == null || r.id().isBlank() ? Ids.prefixed("trf") : r.id();
        TariffRate n = new TariffRate(id, norm(r.originCountry()), norm(r.destCountry()), normalisePrefix(r.hsCodePrefix()),
            r.dutyRate(), r.brokerageFee(), r.surchargeRate(), r.effectiveFrom(), r.effectiveTo(), r.note());
        jdbc.sql("""
            insert into tariff_rate(id, tenant_id, origin_country, dest_country, hs_code_prefix, duty_rate, brokerage_fee, surcharge_rate, effective_from, effective_to, note, updated_at)
            values (:id, :t, :o, :d, :hs, :duty, :brk, :sur, :from, :to, :note, now())
            on conflict (id) do update set origin_country = excluded.origin_country, dest_country = excluded.dest_country, hs_code_prefix = excluded.hs_code_prefix,
              duty_rate = excluded.duty_rate, brokerage_fee = excluded.brokerage_fee, surcharge_rate = excluded.surcharge_rate,
              effective_from = excluded.effective_from, effective_to = excluded.effective_to, note = excluded.note, updated_at = now()
            where tariff_rate.tenant_id = excluded.tenant_id
            """)
            .param("id", n.id()).param("t", TenantContext.require()).param("o", n.originCountry()).param("d", n.destCountry()).param("hs", n.hsCodePrefix())
            .param("duty", n.dutyRate()).param("brk", n.brokerageFee()).param("sur", n.surchargeRate())
            .param("from", Timestamp.from(n.effectiveFrom())).param("to", n.effectiveTo() == null ? null : Timestamp.from(n.effectiveTo())).param("note", n.note()).update();
        return get(n.id());
    }

    public Optional<TariffRate> find(String id) {
        return jdbc.sql("select * from tariff_rate where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public TariffRate get(String id) { return find(id).orElseThrow(() -> new NotFoundException("Tariff rate " + id + " not found")); }

    public int delete(String id) {
        return jdbc.sql("delete from tariff_rate where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).update();
    }

    public Page<TariffRate> list(String originCountry, String destCountry, int offset, int limit) {
        String where = "where tenant_id = :t" + (originCountry != null ? " and origin_country = :o" : "") + (destCountry != null ? " and dest_country = :d" : "");
        var count = jdbc.sql("select count(*) from tariff_rate " + where).param("t", TenantContext.require());
        var rows = jdbc.sql("select * from tariff_rate " + where + " order by origin_country, dest_country, hs_code_prefix, effective_from offset :off limit :lim")
            .param("t", TenantContext.require()).param("off", offset).param("lim", limit);
        if (originCountry != null) { count = count.param("o", norm(originCountry)); rows = rows.param("o", norm(originCountry)); }
        if (destCountry != null) { count = count.param("d", norm(destCountry)); rows = rows.param("d", norm(destCountry)); }
        return Page.of(rows.query(MAPPER).list(), count.query(Long.class).single(), offset, limit);
    }

    /**
     * Candidate rows for a lane effective at {@code at}, most specific first: longest HS prefix, exact origin before
     * wildcard, exact destination before wildcard, latest effective_from.
     */
    public List<TariffRate> candidates(String originCountry, String destCountry, String hsCode, Instant at) {
        String hs = normalisePrefix(hsCode);
        return jdbc.sql("""
            select * from tariff_rate
            where tenant_id = :t
              and (origin_country = :o or origin_country = '*')
              and (dest_country = :d or dest_country = '*')
              and (hs_code_prefix = '' or position(hs_code_prefix in :hs) = 1)
              and effective_from <= :at and (effective_to is null or effective_to > :at)
            order by length(hs_code_prefix) desc,
                     case when origin_country = '*' then 1 else 0 end,
                     case when dest_country = '*' then 1 else 0 end,
                     effective_from desc, id
            """)
            .param("t", TenantContext.require()).param("o", norm(originCountry)).param("d", norm(destCountry)).param("hs", hs).param("at", Timestamp.from(at))
            .query(MAPPER).list();
    }

    static String norm(String country) { return country == null ? "*" : country.trim().toUpperCase(Locale.ROOT); }

    /** HS codes are compared as bare digit strings ("8471.30.01" → "84713001"). */
    static String normalisePrefix(String hs) { return hs == null ? "" : hs.replaceAll("[^0-9A-Za-z]", ""); }
}
