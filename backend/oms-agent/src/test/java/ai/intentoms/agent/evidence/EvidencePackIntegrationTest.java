package ai.intentoms.agent.evidence;

import ai.intentoms.agent.AgentOrderVerifier;
import ai.intentoms.agent.AgentTestBase;
import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-AGT-09 evidence pack shape and the T3 agent_mandate_match hook. */
class EvidencePackIntegrationTest extends AgentTestBase {
    @Autowired EvidencePackService packs;
    @Autowired AgentOrderVerifier verifier;
    @Autowired OrderService orders;
    @Autowired HoldService holds;
    @Autowired HumanTaskService tasks;

    @Test
    void buildsPackForAgentOrderAnd404Otherwise() {
        TestAgent bot = shopper();
        Mandate m = mandate(bot, "buyer-ev", "200.00", "Buy two black tees for my brother");
        String orderId = structured(mcp(bot, toolCall(1, "order_create", Map.of("order", orderArgs("AGT-" + Ids.shortId(), "buyer-ev", "TEE-BLK-M", 2, "28.00", null), "mandateId", m.id(), "buyerAcknowledgement", "consent-123")))).path("orderId").asText();

        AgentOrderVerifier.Verification v = verifier.verify(orderId);
        assertThat(v.recordId()).isNotBlank();
        assertThat(v.requiresHuman()).as("T3 recommendation needs a person").isTrue();
        assertThat(v.holdId()).isNotNull();
        assertThat(v.taskId()).isNotNull();
        assertThat(holds.active("order", orderId)).anyMatch(h -> "agent_verification".equals(h.type()) && "MANDATE_REVIEW".equals(h.reasonCode()));
        assertThat(tasks.list(new TaskQuery("risk_review", "OPEN", null, "order", orderId, null, 0, 10)).items()).anyMatch(t -> "agent_review".equals(t.type()));
        Order o = orders.get(orderId);
        assertThat(o.provenance().mandateDecisionRecordId()).isEqualTo(v.recordId());
        AgentOrderVerifier.Verification again = verifier.verify(orderId);
        assertThat(again.holdId()).as("hold is ensured, not duplicated").isEqualTo(v.holdId());
        assertThat(again.taskId()).isEqualTo(v.taskId());

        Map<String, Object> pack = packs.build(orderId);
        assertThat(pack).containsKeys("orderId", "generatedAt", "agent", "identity", "mandate", "instructionText", "provenance", "injectionScreen", "decisionTrail", "mandateDecision",
            "orderSnapshot", "payments", "fulfilment", "deliveryProof", "ledgerDigest", "hash", "signatures", "buyerAcknowledgement");
        assertThat(pack.get("orderId")).isEqualTo(orderId);
        assertThat(pack.get("buyerAcknowledgement")).isEqualTo("consent-123");
        JsonNode j = Json.toTree(pack);
        assertThat(j.path("agent").path("agentId").asText()).isEqualTo(bot.id());
        assertThat(j.path("agent").path("trustTier").asInt()).isEqualTo(2);
        assertThat(j.path("agent").path("verification").asText()).isEqualTo("hmac");
        assertThat(j.path("identity").path("id").asText()).isEqualTo(bot.id());
        assertThat(j.path("mandate").path("id").asText()).isEqualTo(m.id());
        assertThat(j.path("mandate").path("status").asText()).isEqualTo("consumed");
        assertThat(j.path("mandate").path("scope").isArray()).isTrue();
        assertThat(j.path("instructionText").asText()).contains("black tees");
        assertThat(j.path("injectionScreen").path("flagged").asBoolean()).isFalse();
        assertThat(j.path("decisionTrail").isArray()).isTrue();
        assertThat(j.path("decisionTrail").findValuesAsText("decision")).contains("agent_mandate_match");
        JsonNode first = j.path("decisionTrail").get(0);
        assertThat(first.has("recordId") && first.has("value") && first.has("band") && first.has("confidence") && first.has("layer") && first.has("at")).as(first.toString()).isTrue();
        assertThat(j.path("mandateDecision").path("recordId").asText()).isEqualTo(v.recordId());
        assertThat(CustomerSafeOrderView.isCustomerSafe(Json.toMap(j.path("orderSnapshot")))).isTrue();
        assertThat(j.path("ledgerDigest").asText()).matches("[0-9a-f]{64}");
        assertThat(j.path("hash").asText()).isEqualTo(j.path("ledgerDigest").asText());
        assertThat(j.path("signatures").path("agentVerification").asText()).isEqualTo("hmac");

        AgentOrderVerifier.Verification confirmed = verifier.confirm(orderId, true, "Matches the buyer's instruction");
        assertThat(confirmed.withinMandate()).isTrue();
        assertThat(holds.active("order", orderId)).noneMatch(h -> "agent_verification".equals(h.type()));
        assertThat(orders.get(orderId).provenance().withinMandate()).isTrue();
        assertThat(orderEvents(orderId, "agent.order_confirmed")).hasSize(1);
        assertThat(packs.build(orderId).get("hash")).as("digest changes as the ledger grows").isNotEqualTo(pack.get("hash"));

        Order web = orders.capture(new CaptureRequest("web", "W-" + Ids.shortId(), "standard_ship", "cust-web", new CaptureRequest.CustomerInput("cust-web", "Jane", "j@example.com", null, "gold", false, null, Map.of()),
            List.of(new CaptureRequest.LineInput("L1", "TEE-BLK-M", null, 1, ai.intentoms.finance.model.Money.usd("28.00"), null, null, null)),
            List.of(new CaptureRequest.AddressInput("ship_to", "Jane", "1 Market St", null, "San Francisco", "CA", "94105", "US", null, null)), List.of(),
            List.of(new CaptureRequest.TenderInput("card", "tok_visa", ai.intentoms.finance.model.Money.usd("100.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null), null);
        assertThatThrownBy(() -> packs.build(web.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> verifier.verify(web.id())).isInstanceOf(NotFoundException.class);
    }
}
