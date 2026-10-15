package ai.intentoms.copilot.ops;

import ai.intentoms.copilot.llm.JsonSchema;
import ai.intentoms.copilot.support.LlmStubs;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The strict selection schema accepts null parameters and rejects unknown templates (GR-04). */
class OpsSelectionSchemaTest {
    @Test
    void acceptsNullParametersAndRejectsUnknownTemplates() {
        JsonSchema schema = OpsCopilotService.selectionSchema(new OpsQueryCatalog().names());
        Map<String, Object> params = new HashMap<>();
        for (String k : List.of("channel", "orderType", "nodeId", "state", "destState", "hours", "limit", "sku", "customerRef", "orderId", "exceptionType", "decisionName", "threshold")) params.put(k, null);
        params.put("hours", 24);
        params.put("destState", "TX");
        List<String> problems = schema.validate(Json.tree(LlmStubs.json(Map.of("template", "orders_by_status", "parameters", params, "reason", "x"))));
        assertThat(problems).as(problems.toString()).isEmpty();
        assertThat(schema.validate(Json.tree(LlmStubs.json(Map.of("template", "delete_everything", "parameters", params, "reason", "x"))))).isNotEmpty();
        Map<String, Object> missing = new HashMap<>(params);
        missing.remove("limit");
        assertThat(schema.validate(Json.tree(LlmStubs.json(Map.of("template", "orders_by_status", "parameters", missing, "reason", "x"))))).isNotEmpty();
    }
}
