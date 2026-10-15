package ai.intentoms.copilot.ops;

import java.util.List;
import java.util.Map;

/**
 * One whitelisted, parameterised query of the ops copilot (FR-MON-08). {@code sql} contains the marker {@code {filters}}
 * where the bound optional parameter clauses are inserted; every clause uses JdbcClient named parameters and every
 * template filters on {@code :tenant}. The model only ever picks a template name and typed parameter values.
 */
public record OpsQueryTemplate(String name, String description, String sql, List<Param> params, List<String> keywords, String example) {
    public enum ParamType { CHANNEL, ORDER_TYPE, NODE, STATE, US_STATE, HOURS, LIMIT, SKU, CUSTOMER_REF, EXCEPTION_TYPE, DECISION, THRESHOLD }

    /** {@code clause} is appended to the filters when the parameter is bound (null for parameters used directly in the SQL). */
    public record Param(String name, ParamType type, boolean required, String clause, String description) {}

    public Map<String, Object> describe() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("parameters", params.stream().map(p -> Map.of("name", p.name(), "type", p.type().name().toLowerCase(), "required", p.required(), "description", p.description())).toList());
        m.put("example", example);
        return m;
    }
}
