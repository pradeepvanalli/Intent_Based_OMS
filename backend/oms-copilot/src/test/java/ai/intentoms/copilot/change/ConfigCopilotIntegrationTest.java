package ai.intentoms.copilot.change;

import ai.intentoms.copilot.CopilotTestBase;
import ai.intentoms.copilot.support.LlmStubs;
import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.StateModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-CFG-01/02, FR-SM-12, GR-14: propose (with a deliberately invalid artefact repaired on the second round-trip) →
 * stage as drafts only → apply by a human with the owning permission.
 */
class ConfigCopilotIntegrationTest extends CopilotTestBase {
    @Autowired ConfigCopilotService copilot;
    @Autowired DraftArtifactService artifacts;
    @Autowired DecisionStudioService studio;
    @Autowired StateModelRegistry stateModelRegistry;
    @Autowired GoldenCaseRepository golden;

    static final String REQUEST = "Gold customers with a receipt get an instant refund under $250; agent orders over $500 shipping to California need a risk review; raise the split penalty to $15";

    @Test
    void proposeRepairsInvalidArtefactsStagesDraftsAndAppliesByHumans() {
        String goodRule = "input.customer.tier == 'gold' && input.return.receipt == true && input.return.value_usd < 250";
        String brokenRule = "input.customer.tier == 'gold' &&& input.return.receipt";
        String returnRiskBroken = withRule(readDemo("decisions/return_risk.yaml"), brokenRule);
        String returnRiskFixed = withRule(readDemo("decisions/return_risk.yaml"), goodRule);
        String lineModelV4 = riskReviewModel(readDemo("state-models/order_line.standard_ship.yaml"));
        List<Map<String, Object>> tests = List.of(
            Map.of("caseId", "gold_receipt_small", "input", Json.write(Map.of("return", Map.of("reason", "changed_mind", "days_since_delivery", 3, "receipt", true, "item_condition", "matches", "value_usd", 120),
                "order", Map.of("channel", "web", "final_sale", false), "customer", Map.of("tier", "gold", "history", Map.of("returns_12m", 0, "not_received_claims", 0, "flags", List.of())), "policy", Map.of("window_days", 30))),
                "expected", Json.write(Map.of("value", 0)), "notes", "fires the gold fast lane"),
            Map.of("caseId", "gold_no_receipt", "input", Json.write(Map.of("return", Map.of("reason", "changed_mind", "days_since_delivery", 3, "receipt", false, "item_condition", "matches", "value_usd", 120),
                "order", Map.of("channel", "web", "final_sale", false), "customer", Map.of("tier", "gold", "history", Map.of("returns_12m", 0, "not_received_claims", 0, "flags", List.of())), "policy", Map.of("window_days", 30))),
                "expected", Json.write(Map.of("value", "on_inspection")), "notes", "does not fire without a receipt"));
        Map<String, Object> proposal = Map.of(
            "summary", "Adds a gold fast lane to return_risk, a risk review state for agent orders to California, and raises the split penalty.",
            "assumptions", List.of("Receipt means the return carries a receipt flag"),
            "questions", List.of(),
            "changes", List.of(
                change("c1", "decision_rule", "return_risk", "Gold fast lane as an L0 rule", returnRiskBroken, "T2", tests),
                change("c2", "state_model", "order_line.standard_ship", "Risk review state for agent orders over $500 to CA", lineModelV4, "T3", List.of()),
                change("c3", "tenant_config", "tenant_config", "Split penalty to $15", "sourcing.split_penalty_usd: 15.0\n", "T1", List.of()),
                change("c4", "tenant_config", "tenant_config", "Unknown key the model made up", "ops.secret_backdoor: true\n", "T1", List.of())));
        LlmStubs.stub(LLM, "change_set", proposal);
        LlmStubs.stub(LLM, "change_repair", Map.of("changes", List.of(Map.of("id", "c1", "after", returnRiskFixed), Map.of("id", "c4", "after", "ops.secret_backdoor: true\n"))));

        ChangeSet cs = copilot.propose(new ChangeRequest(REQUEST, null, Map.of("ticket", "OPS-42")));

        assertThat(LLM.getAllServeEvents()).hasSize(2);   // proposal + exactly one repair round-trip
        assertThat(cs.status()).isEqualTo("proposed");
        assertThat(cs.changes()).hasSize(4);
        ProposedChange c1 = cs.change("c1");
        assertThat(c1.repaired()).isTrue();
        assertThat(c1.validation().ok()).as(c1.validation().issues().toString()).isTrue();
        Map<String, Object> repairedRule = rules(c1.after()).stream().filter(r -> "gold_fast_lane".equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(repairedRule).containsEntry("when", goodRule).containsEntry("then", 0);
        assertThat(c1.before()).contains("decision: return_risk");
        assertThat(c1.diff()).contains("+").contains("gold_fast_lane");
        assertThat(c1.requiresApprovals()).isEqualTo(2);   // T2 needs two approvers
        assertThat(c1.tests()).hasSize(2);
        ProposedChange c2 = cs.change("c2");
        assertThat(c2.validation().ok()).as(c2.validation().issues().toString()).isTrue();
        assertThat(c2.before()).contains("version: 3");
        assertThat(cs.change("c3").validation().ok()).isTrue();
        ProposedChange c4 = cs.change("c4");
        assertThat(c4.validation().ok()).isFalse();
        assertThat(c4.validation().issues()).anyMatch(i -> i.contains("whitelist"));
        assertThat(configEvents("copilot:" + cs.id(), ConfigCopilotService.EVENT_PROPOSED)).hasSize(1);
        assertThat(copilot.get(cs.id()).changes()).hasSize(4);
        assertThat(copilot.list(null, 0, 10).items()).extracting(ChangeSet::id).contains(cs.id());

        // Selecting an invalid change explicitly is refused; staging everything skips it.
        assertThatThrownBy(() -> copilot.stage(cs.id(), List.of("c4"))).isInstanceOf(ValidationException.class);
        ChangeSet staged = copilot.stage(cs.id(), List.of());
        assertThat(staged.status()).isEqualTo("staged");
        Map<String, Object> s1 = staged.change("c1").staged();
        assertThat(s1).containsEntry("kind", "decision_draft").containsEntry("name", "return_risk");
        assertThat(s1.get("ref")).isEqualTo("return_risk@" + s1.get("version"));
        int draftVersion = ((Number) s1.get("version")).intValue();
        DecisionDefinition draft = studio.get("return_risk", draftVersion).definition();
        assertThat(draft.status()).isEqualTo("draft");                      // never published (GR-14)
        assertThat(draft.changeReason()).startsWith("copilot: ").contains("instant refund");
        assertThat(draft.ticket()).isEqualTo("copilot:" + cs.id());
        assertThat(draft.rules()).anyMatch(r -> r.id().equals("gold_fast_lane"));
        assertThat(studio.summaries().stream().filter(m -> m.get("name").equals("return_risk")).findFirst().orElseThrow().get("liveVersion")).isEqualTo(2);
        assertThat(staged.change("c4").staged()).isNull();
        Map<String, Object> s2 = staged.change("c2").staged();
        assertThat(s2).containsEntry("kind", "artifact").containsEntry("applyPermission", "statemodel.publish");
        String modelArtifact = (String) s2.get("ref");
        String configArtifact = (String) staged.change("c3").staged().get("artifactId");
        assertThat(artifacts.list("draft", cs.id())).extracting(DraftArtifact::kind).containsExactlyInAnyOrder(ChangeKind.state_model, ChangeKind.tenant_config);
        assertThat(stateModelRegistry.latest("order_line.standard_ship").version()).isEqualTo(3);   // still unpublished

        // Apply needs the owning permission and a human.
        actAs("viewer", Set.of("decision.read"));
        assertThatThrownBy(() -> artifacts.apply(configArtifact, "approved in change review")).isInstanceOf(ForbiddenException.class);
        actAs("admin", Set.of("*"));
        assertThatThrownBy(() -> artifacts.apply(configArtifact, " ")).isInstanceOf(ValidationException.class);
        DraftArtifact applied = artifacts.apply(configArtifact, "approved in change review");
        assertThat(applied.status()).isEqualTo("applied");
        assertThat(config.getDouble("sourcing.split_penalty_usd", 0)).isEqualTo(15.0);
        List<Map<String, Object>> audit = jdbc.sql("select action, object_id, reason from audit_log where tenant_id = :t and action = 'copilot.change_applied'").param("t", TENANT).query().listOfRows();
        assertThat(audit).anyMatch(r -> configArtifact.equals(r.get("object_id")) && "approved in change review".equals(r.get("reason")));
        assertThat(configEvents("copilot:" + cs.id(), "copilot.change_applied")).hasSize(1);
        DraftArtifact published = artifacts.publishStateModelDraft(modelArtifact, "risk review for agent orders approved");
        assertThat(published.result()).containsEntry("version", 4);
        assertThat(stateModelRegistry.latest("order_line.standard_ship").hasState("risk_review")).isTrue();
        assertThatThrownBy(() -> artifacts.apply(modelArtifact, "again")).hasMessageContaining("not a draft");

        // Golden cases land in the decision's golden set so the evaluation gate exercises the new rule.
        int before = golden.count("return_risk_gold");
        Map<String, Object> added = copilot.addTestsToGoldenSet(cs.id(), "c1");
        assertThat(added).containsEntry("goldenSet", "return_risk_gold").containsEntry("added", 2);
        assertThat(golden.count("return_risk_gold")).isEqualTo(before + 2);

        // Deterministic explanation works when the model is not used; the model variant is a plain summary.
        LlmStubs.stub(LLM, "change_explanation", Map.of("summary", "Gold customers with a receipt and value under 250 get score 0.", "impacts", List.of("returns team"), "risks", List.of("verify value threshold")));
        Map<String, Object> explained = copilot.explainChange(cs.id(), "c1");
        assertThat(explained).containsEntry("generated", true).containsEntry("kind", "decision_rule");
        assertThat((String) explained.get("summary")).contains("score 0");
    }

    @Test
    void rejectAndPermissionRules() {
        LlmStubs.stub(LLM, "change_set", Map.of("summary", "s", "assumptions", List.of(), "questions", List.of(),
            "changes", List.of(change("c1", "tenant_config", "tenant_config", "r", "returns.window_days: 45\n", "T1", List.of()))));
        ChangeSet cs = copilot.propose(new ChangeRequest("Extend the return window to 45 days", "returns", null));
        assertThat(cs.change("c1").validation().ok()).isTrue();
        ChangeSet rejected = copilot.reject(cs.id(), "not this quarter");
        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.meta()).containsEntry("rejectReason", "not this quarter");
        assertThatThrownBy(() -> copilot.stage(cs.id(), List.of())).hasMessageContaining("rejected");
        actAs("cs", Set.of("order.read"));
        assertThatThrownBy(() -> copilot.propose(new ChangeRequest("anything", null, null))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void emptyProposalWithQuestionsIsReportedNotStored() {
        LlmStubs.stub(LLM, "change_set", Map.of("summary", "Need more detail", "assumptions", List.of(), "questions", List.of("Which channel?"), "changes", List.of()));
        assertThatThrownBy(() -> copilot.propose(new ChangeRequest("make it better", null, null))).isInstanceOf(ValidationException.class).hasMessageContaining("Which channel?");
    }

    // ------------------------------------------------------------------ fixtures

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rules(String yaml) { return (List<Map<String, Object>>) (List<?>) Json.yamlMap(yaml).get("rules"); }

    static Map<String, Object> change(String id, String kind, String target, String rationale, String after, String tier, List<Map<String, Object>> tests) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("kind", kind); m.put("target", target); m.put("rationale", rationale); m.put("after", after); m.put("riskTier", tier); m.put("tests", tests);
        return m;
    }

    /** return_risk YAML with an extra L0 fast-lane rule. */
    @SuppressWarnings("unchecked")
    static String withRule(String yaml, String when) {
        Map<String, Object> m = Json.yamlMap(yaml);
        List<Object> rules = new ArrayList<>((List<Object>) m.get("rules"));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", "gold_fast_lane"); rule.put("when", when); rule.put("then", 0); rule.put("reason", "gold_receipt_fast_lane"); rule.put("priority", 35);
        rules.add(rule);
        m.put("rules", rules);
        return Json.toYaml(m);
    }

    /** order_line.standard_ship v4 with a risk_review state for agent orders over $500 shipping to California. */
    @SuppressWarnings("unchecked")
    static String riskReviewModel(String yaml) {
        Map<String, Object> m = Json.yamlMap(yaml);
        m.put("version", 4);
        List<Object> states = new ArrayList<>((List<Object>) m.get("states"));
        states.add(Map.of("id", "risk_review", "sla", Map.of("maxAge", "PT4H", "onBreach", "playbook.agent_review")));
        m.put("states", states);
        List<Object> transitions = new ArrayList<>((List<Object>) m.get("transitions"));
        Map<String, Object> toReview = new LinkedHashMap<>();
        toReview.put("from", "awaiting_sourcing"); toReview.put("to", "risk_review"); toReview.put("trigger", "decision.sourcing_plan");
        toReview.put("guards", List.of("order.agent_order == true && order.total > 500 && order.destination_state == 'CA'"));
        toReview.put("actions", List.of("create_review_task"));
        transitions.add(0, toReview);
        transitions.add(Map.of("from", "risk_review", "to", "released", "trigger", "api.approve_review", "permission", "order.release", "actions", List.of("reserve_inventory", "send_release_to_wms")));
        transitions.add(Map.of("from", "risk_review", "to", "cancelled", "trigger", "api.reject_review", "permission", "order.cancel", "actions", List.of("void_or_refund")));
        m.put("transitions", transitions);
        return Json.toYaml(m);
    }
}
