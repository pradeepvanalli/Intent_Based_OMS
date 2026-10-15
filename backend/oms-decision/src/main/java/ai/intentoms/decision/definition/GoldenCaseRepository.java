package ai.intentoms.decision.definition;

import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Labelled golden cases per golden set (GR-11). */
@Repository
public class GoldenCaseRepository {
    private final JdbcClient jdbc;

    public GoldenCaseRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void upsert(GoldenCase c) {
        jdbc.sql("insert into decision_golden_case(tenant_id, golden_set, case_id, input, expected, notes) values (:t, :g, :c, :i, :e, :n) " +
                "on conflict (tenant_id, golden_set, case_id) do update set input = excluded.input, expected = excluded.expected, notes = excluded.notes")
            .param("t", TenantContext.require()).param("g", c.goldenSet()).param("c", c.caseId()).param("i", Jsonb.of(c.input())).param("e", Jsonb.of(c.expected())).param("n", c.notes()).update();
    }

    public void upsertAll(List<GoldenCase> cases) { cases.forEach(this::upsert); }

    public List<GoldenCase> forSet(String goldenSet) {
        return jdbc.sql("select golden_set, case_id, input::text as input, expected::text as expected, notes from decision_golden_case where tenant_id = :t and golden_set = :g order by case_id")
            .param("t", TenantContext.require()).param("g", goldenSet)
            .query((rs, i) -> new GoldenCase(rs.getString("golden_set"), rs.getString("case_id"), Json.readMap(rs.getString("input")), Json.readMap(rs.getString("expected")), rs.getString("notes")))
            .list();
    }

    public int count(String goldenSet) {
        return jdbc.sql("select count(*) from decision_golden_case where tenant_id = :t and golden_set = :g").param("t", TenantContext.require()).param("g", goldenSet).query(Integer.class).single();
    }

    public List<String> sets() {
        return jdbc.sql("select distinct golden_set from decision_golden_case where tenant_id = :t order by golden_set").param("t", TenantContext.require()).query(String.class).list();
    }

    public int delete(String goldenSet) {
        return jdbc.sql("delete from decision_golden_case where tenant_id = :t and golden_set = :g").param("t", TenantContext.require()).param("g", goldenSet).update();
    }
}
