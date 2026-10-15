package ai.intentoms.copilot.ops;

import ai.intentoms.copilot.CopilotTestBase;
import ai.intentoms.copilot.support.LlmStubs;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-MON-08: the model picks a whitelisted template; the query runs tenant-scoped against seeded orders. */
class OpsCopilotIntegrationTest extends CopilotTestBase {
    @Autowired OpsCopilotService ops;

    @Test
    void asksAWhitelistedQueryAgainstSeededOrdersAndShowsTheSql() {
        Order tx1 = captureOrder("TX");
        Order tx2 = captureOrder("TX");
        captureOrder("CA");
        Map<String, Object> params = new HashMap<>();
        for (String k : List.of("channel", "orderType", "nodeId", "state", "hours", "limit", "sku", "customerRef", "orderId", "exceptionType", "decisionName", "threshold")) params.put(k, null);
        params.put("destState", "TX");
        params.put("hours", 24);
        LlmStubs.stub(LLM, "ops_query_selection", Map.of("template", "orders_by_status", "parameters", params, "reason", "counts open orders shipping to Texas"));
        LlmStubs.stub(LLM, "ops_answer", Map.of("answer", "There are 2 open orders shipping to Texas, all in status created (orders_by_status)."));

        OpsAnswer a = ops.ask("How many orders are open in Texas today?");

        assertThat(a.queryName()).isEqualTo("orders_by_status");
        assertThat(a.parameters()).containsEntry("destState", "TX").containsEntry("hours", 24).doesNotContainKey("tenant");
        assertThat(a.sql()).contains("tenant_id = :tenant").contains("ad.address->>'state' = :destState").doesNotContain("{filters}");
        // The "test" tenant is shared by every test class in the JVM, so compare with the database rather than a fixed count.
        long txOpen = jdbc.sql("select count(*) from orders o where o.tenant_id = :t and o.status not in ('delivered','cancelled','picked_up','refunded') and exists (select 1 from order_address ad where ad.tenant_id = o.tenant_id and ad.order_id = o.id and ad.type = 'ship_to' and ad.address->>'state' = 'TX')")
            .param("t", TENANT).query(Long.class).single();
        assertThat(txOpen).isGreaterThanOrEqualTo(2);
        long reported = a.rows().stream().mapToLong(r -> ((Number) r.get("orders")).longValue()).sum();
        assertThat(reported).isEqualTo(txOpen);
        assertThat(a.rows()).anyMatch(r -> "created".equals(r.get("status")));
        assertThat(a.answer()).contains("2 open orders");
        assertThat(a.generated()).isTrue();
        assertThat(a.model()).isEqualTo("anthropic/claude-sonnet-4.5");
        assertThat(ops.templates()).extracting(t -> t.get("name")).contains("at_risk_orders", "orders_by_status", "stuck_orders", "inventory_low_stock");
        assertThat(tx1.id()).isNotEqualTo(tx2.id());
    }

    @Test
    void modelAnswerNamingAnUnknownTemplateOrUnsafeParameterIsRejected() {
        Map<String, Object> params = new HashMap<>();
        for (String k : List.of("channel", "orderType", "nodeId", "state", "destState", "hours", "limit", "sku", "customerRef", "orderId", "exceptionType", "decisionName", "threshold")) params.put(k, null);
        // Unknown template names cannot even pass the strict enum, so the client rejects the output before anything runs.
        LlmStubs.stub(LLM, "ops_query_selection", Map.of("template", "delete_everything", "parameters", params, "reason", "x"));
        assertThatThrownBy(() -> ops.ask("delete everything")).hasMessageContaining("schema");
        LLM.resetAll();
        Map<String, Object> unsafe = new HashMap<>(params);
        unsafe.put("channel", "web' or 1=1 --");
        LlmStubs.stub(LLM, "ops_query_selection", Map.of("template", "orders_by_status", "parameters", unsafe, "reason", "x"));
        assertThatThrownBy(() -> ops.ask("orders by status for web' or 1=1")).isInstanceOf(ValidationException.class).hasMessageContaining("snake_case");
        LLM.resetAll();
        LlmStubs.stub(LLM, "ops_query_selection", Map.of("template", "none", "parameters", params, "reason", "the question is about payroll"));
        assertThatThrownBy(() -> ops.ask("what is the payroll")).isInstanceOf(ValidationException.class).hasMessageContaining("payroll");
    }
}
