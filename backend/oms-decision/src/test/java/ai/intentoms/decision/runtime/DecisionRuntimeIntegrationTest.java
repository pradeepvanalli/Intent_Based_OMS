package ai.intentoms.decision.runtime;

import ai.intentoms.decision.DecisionTestBase;
import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.DefinitionCache;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.CustomerSafeTraceView;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.DecisionTrace;
import ai.intentoms.decision.model.Invocation;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionRuntimeIntegrationTest extends DecisionTestBase {
    @Autowired DecisionService decisions;
    @Autowired DecisionRuntime runtime;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired DecisionDefinitionParser parser;
    @Autowired DefinitionCache cache;
    @Autowired TenantConfigStore config;
    @Autowired DecisionEscalationRepository escalations;

    @AfterEach
    void cleanConfig() {
        config.delete(ConfigKeys.DECISION_KILL_SWITCH_PREFIX + "exception_classify");
        config.delete(ConfigKeys.AI_BUDGET_USD_PER_DAY);
        config.delete(ConfigKeys.MARGIN_FLOOR);
        for (var row : definitions.byStatus("exception_classify", "shadow", "canary")) definitions.setStatus("exception_classify", row.definition().version(), "retired", null);
        cache.invalidateAll();
    }

    @Test
    void fulfilmentIntentWithHeuristicPersistsRecordEventAndEscalation() {
        Map<String, Object> input = intentInput("Please ship everything together in one delivery", "ord_rt_1");
        DecisionResult r = decisions.decide("fulfilment_intent", input, DecisionOptions.live("order", "ord_rt_1", Actor.user("tester")));

        assertThat(r.value()).isEqualTo("ship_complete");
        assertThat(r.provider()).isEqualTo("heuristic");                   // jev disabled in tests → fallback provider
        assertThat(r.band()).isEqualTo(Band.CONFIRM);                      // heuristic confidence 0.55-0.6 on a T1 decision
        assertThat(r.layer()).isEqualTo(Layer.L1_SYSTEM_ONE);              // T1 confirm band: no human hand-off, caller applies the default
        assertThat(r.action()).isEqualTo(DecisionResult.CONFIRM);
        assertThat(r.requiresHuman()).isFalse();
        assertThat(r.escalationId()).isNull();
        assertThat(r.reasonCodes()).contains("confirm_default_applied");
        assertThat(r.answers()).containsKeys("intent", "split_ok", "is_gift");
        assertThat(escalations.find("none")).isEmpty();

        DecisionRecord rec = decisions.record(r.recordId());
        assertThat(rec.mode()).isEqualTo(Mode.LIVE);
        assertThat(rec.subjectId()).isEqualTo("ord_rt_1");
        assertThat(rec.inputHash()).isEqualTo(Json.sha256(input));
        assertThat(Json.write(rec.state())).doesNotContain("jane@example.com").doesNotContain("Jane Doe");   // GR-09 minimal + tokenised
        assertThat(Json.write(rec.input())).contains("jane@example.com");                                      // full input kept for replay
        assertThat(decisions.forSubject("order", "ord_rt_1")).extracting(DecisionRecord::id).contains(r.recordId());

        var evs = events("decision", r.recordId(), "decision.evaluated");
        assertThat(evs).hasSize(1);
        Map<String, Object> payload = Json.readMap((String) evs.get(0).get("payload"));
        assertThat(payload).containsEntry("decision", "fulfilment_intent").containsEntry("layer", "L1_SYSTEM_ONE").containsEntry("value", "ship_complete");
        assertThat(Json.readMap((String) evs.get(0).get("metadata"))).containsEntry("subjectType", "order").containsEntry("subjectId", "ord_rt_1");
    }

    @Test
    void tierZeroActsAndCachesByInputHash() {
        Map<String, Object> input = Map.of("exception", Map.of("source", "carrier", "code", "DELAY", "message", "Carrier delay, missed scan at hub, delivery late"), "order", Map.of("channel", "web", "status", "shipped", "nonce", System.nanoTime()));
        DecisionResult first = decisions.decide("exception_classify", input, DecisionOptions.live("exception", "exc_1", Actor.system("test")));
        assertThat(first.value()).isEqualTo("carrier_delay");
        assertThat(first.band()).isEqualTo(Band.ACT);
        assertThat(first.action()).isEqualTo(DecisionResult.ACT);
        assertThat(first.requiresHuman()).isFalse();
        assertThat(first.layer()).isEqualTo(Layer.L1_SYSTEM_ONE);
        DecisionResult second = decisions.decide("exception_classify", input, DecisionOptions.live("exception", "exc_1", Actor.system("test")));
        // OC-01: a cache hit is a decision too, logged as its own record pointing at the answer it reused.
        assertThat(second.recordId()).isNotEqualTo(first.recordId());
        assertThat(second.value()).isEqualTo(first.value());
        assertThat(second.costUsd()).isZero();
        assertThat(second.details()).containsEntry("cached", true).containsEntry("cachedFrom", first.recordId());
        DecisionRecord logged = decisions.record(second.recordId());
        assertThat(logged.mode()).isEqualTo(Mode.LIVE);
        assertThat(logged.details()).containsEntry("cached", true).containsEntry("cachedFrom", first.recordId());
        assertThat(logged.chosenArm()).isEqualTo("carrier_delay");
        assertThat(logged.arms()).contains("carrier_delay").hasSizeGreaterThan(1);
        assertThat(events("decision", second.recordId(), "decision.evaluated")).hasSize(1);
    }

    /** OC-01: every candidate is logged as an arm, and the arm is what was applied, not what the model said. */
    @Test
    void armsListEveryCandidateAndChosenArmIsTheAppliedValue() {
        Map<String, Object> input = intentInput("Please ship everything together in one delivery", "ord_arms_1");
        DecisionResult r = decisions.decide("fulfilment_intent", input, DecisionOptions.live("order", "ord_arms_1", Actor.user("tester")));
        assertThat(r.action()).isEqualTo(DecisionResult.CONFIRM);
        DecisionRecord rec = decisions.record(r.recordId());
        // every choice option is an arm
        assertThat(rec.arms()).contains("ship_complete", "ship_as_available").hasSizeGreaterThan(2);
        // T1 confirm band with no L2: the caller applies the default, so that is the logged arm; the model's answer is kept
        Object dflt = rec.details().get("confirmFallback") instanceof Map<?, ?> m ? m.get("defaultValue") : null;
        if (dflt != null && !String.valueOf(dflt).equals(String.valueOf(rec.value()))) {
            assertThat(rec.chosenArm()).isEqualTo(String.valueOf(dflt));
            assertThat(rec.details()).containsEntry("modelAnswer", "ship_complete").containsEntry("chosenArmSource", "confirm_default");
        } else {
            assertThat(rec.chosenArm()).isEqualTo(String.valueOf(rec.value()));
        }
        assertThat(rec.arms()).contains(rec.chosenArm());
        assertThat(rec.propensity()).isEqualTo(1.0);
    }

    @Test
    void tierZeroLowConfidenceYieldsEscapeValueAndStillActs() {
        DecisionResult r = decisions.decide("exception_classify", Map.of("exception", Map.of("source", "x", "message", ""), "order", Map.of("nonce", System.nanoTime())), DecisionOptions.live("exception", "exc_2", Actor.system("test")));
        assertThat(r.value()).isEqualTo("unclear");
        assertThat(r.action()).isEqualTo(DecisionResult.ACT);
        assertThat(r.reasonCodes()).contains("escape_option");
    }

    @Test
    void l0RulesWinAndCarryReasonCodes() {
        Map<String, Object> input = Map.of("return", Map.of("reason", "changed_mind", "days_since_delivery", 45, "receipt", "present", "item_condition", "matches"),
            "order", Map.of("final_sale", false), "customer", Map.of("history", Map.of("returns_12m", 1, "not_received_claims", 0, "flags", List.of("first_return"))), "policy", Map.of("window_days", 30));
        DecisionResult r = decisions.decide("return_risk", input, DecisionOptions.live("return", "rma_1", Actor.system("test")));
        assertThat(r.layer()).isEqualTo(Layer.L0_RULE);
        assertThat(r.value()).isEqualTo("deny_out_of_window");
        assertThat(r.rulesFired()).containsExactly("out_of_window");
        assertThat(r.reasonCodes()).contains("out_of_window");
        assertThat(r.band()).isEqualTo(Band.ACT);
        assertThat(r.provider()).isNull();
        assertThat(r.costUsd()).isZero();
    }

    @Test
    void blockingRuleAndTierThreeNeverAct() {
        Map<String, Object> over = Map.of("mandate", Map.of("instruction_text", "buy running shoes under 200", "spend_limit", 200, "scope", "shoes"), "order", Map.of("total", 450, "items_summary", "running shoes"), "agent", Map.of("trust_tier", 2));
        DecisionResult blocked = decisions.decide("agent_mandate_match", over, DecisionOptions.live("order", "ord_a1", Actor.agent("shopbot")));
        assertThat(blocked.action()).isEqualTo(DecisionResult.BLOCKED);
        assertThat(blocked.layer()).isEqualTo(Layer.L0_RULE);
        assertThat(blocked.reasonCodes()).contains("over_mandate_spend_limit");

        Map<String, Object> within = Map.of("mandate", Map.of("instruction_text", "buy running shoes under 200", "spend_limit", 200, "scope", "running shoes"), "order", Map.of("total", 150, "items_summary", "running shoes size 10"), "agent", Map.of("trust_tier", 2));
        DecisionResult t3 = decisions.decide("agent_mandate_match", within, DecisionOptions.live("order", "ord_a2", Actor.agent("shopbot")));
        assertThat(t3.band()).isNotEqualTo(Band.ACT);
        assertThat(t3.action()).isEqualTo(DecisionResult.ESCALATE);
        assertThat(t3.requiresHuman()).isTrue();
        assertThat(t3.layer()).isEqualTo(Layer.L3_HUMAN);
        assertThat(t3.value()).isInstanceOf(Boolean.class);               // recommendation only
        assertThat(t3.escalationId()).isNotNull();
    }

    @Test
    void killSwitchForcesDefault() {
        config.set(ConfigKeys.DECISION_KILL_SWITCH_PREFIX + "exception_classify", true);
        DecisionResult r = decisions.decide("exception_classify", Map.of("exception", Map.of("message", "Carrier delay"), "order", Map.of("nonce", System.nanoTime())), DecisionOptions.live("exception", "exc_k", Actor.system("test")));
        assertThat(r.layer()).isEqualTo(Layer.DEFAULT);
        assertThat(r.action()).isEqualTo(DecisionResult.DEFAULT);
        assertThat(r.value()).isEqualTo("unclear");
        assertThat(r.details()).containsEntry("defaultReason", "kill_switch");
        assertThat(r.provider()).isNull();
    }

    @Test
    void exhaustedBudgetDegradesToDefault() {
        config.set(ConfigKeys.AI_BUDGET_USD_PER_DAY, 0.0);
        DecisionResult r = decisions.decide("exception_classify", Map.of("exception", Map.of("message", "Payment declined"), "order", Map.of("nonce", System.nanoTime())), DecisionOptions.live("exception", "exc_b", Actor.system("test")));
        assertThat(r.layer()).isEqualTo(Layer.DEFAULT);
        assertThat(r.details()).containsEntry("defaultReason", "budget_exceeded");
    }

    @Test
    void failedPostCheckDiscardsAnswerAndLogsEvent() {
        config.set(ConfigKeys.MARGIN_FLOOR, 0.12);
        Map<String, Object> input = Map.of("order", Map.of("channel", "web", "notes", "ship everything together please", "intent_choices", List.of()), "customer", Map.of("tier", "gold"), "chosen_plan", Map.of("margin", 0.03, "packages", 1));
        DecisionResult r = decisions.decide("fulfilment_intent", input, DecisionOptions.live("order", "ord_pc", Actor.system("test")));
        assertThat(r.layer()).isEqualTo(Layer.DEFAULT);
        assertThat(r.value()).isEqualTo("channel_default_policy");
        assertThat(r.details()).containsEntry("defaultReason", "postcheck_failed").containsEntry("discardedValue", "ship_complete");
        assertThat(r.reasonCodes()).contains("postcheck_failed");
        assertThat(events("decision", r.recordId(), "decision.postcheck_failed")).hasSize(1);
        assertThat(r.answers()).containsKey("intent");                     // kept for the trace, not acted on
    }

    @Test
    void shadowVersionRunsAsynchronouslyWithoutActing() throws Exception {
        int v = definitions.nextVersion("exception_classify");
        DecisionDefinition shadow = parser.parse(TestYaml.demo("exception_classify").replace("version: 1", "version: " + v)).withStatus("shadow", 0, "shadow test", null, null);
        definitions.insert(shadow, "user:tester");
        definitions.setStatus("exception_classify", v, "shadow", 0);
        cache.invalidate("exception_classify");
        Map<String, Object> input = Map.of("exception", Map.of("message", "Store rejected the release, could not fulfil"), "order", Map.of("nonce", System.nanoTime()));
        DecisionResult live = decisions.decide("exception_classify", input, DecisionOptions.live("exception", "exc_s", Actor.system("test")));
        assertThat(live.version()).isEqualTo(1);
        String hash = Json.sha256(input);
        List<Map<String, Object>> rows = List.of();
        for (int i = 0; i < 50 && rows.isEmpty(); i++) {
            Thread.sleep(100);
            rows = jdbc.sql("select id, escalation_id, requires_human from decision_record where tenant_id = :t and decision = 'exception_classify' and mode = 'SHADOW' and version = :v and input_hash = :h")
                .param("t", TENANT).param("v", v).param("h", hash).query().listOfRows();
        }
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("escalation_id")).isNull();
        assertThat(rows.get(0).get("id")).isNotEqualTo(live.recordId());
    }

    @Test
    void canarySelectionIsStablePerSubject() {
        int v = definitions.nextVersion("exception_classify");
        DecisionDefinition canary = parser.parse(TestYaml.demo("exception_classify").replace("version: 1", "version: " + v)).withStatus("canary", 50, "canary test", null, null);
        definitions.insert(canary, "user:tester");
        definitions.setStatus("exception_classify", v, "canary", 50);
        cache.invalidate("exception_classify");
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String subject = "exc_c_" + i;
            Map<String, Object> input = Map.of("exception", Map.of("message", "Payment authorization declined"), "order", Map.of("subject", subject, "nonce", System.nanoTime()));
            DecisionOptions opts = new DecisionOptions("exception", subject, null, Mode.SIMULATION, null, Actor.system("test"), Map.of());
            int a = runtime.resolve("exception_classify", opts).selected().version();
            int b = runtime.resolve("exception_classify", opts).selected().version();
            assertThat(a).isEqualTo(b);
            assertThat(decisions.decide("exception_classify", input, opts).version()).isEqualTo(a);
            seen.add(a);
        }
        assertThat(seen).containsExactlyInAnyOrder(1, v);
    }

    @Test
    void whyLabelAndOverride() {
        DecisionResult r = decisions.decide("fulfilment_intent", intentInput("I need the charger urgently, rest can come later", "ord_w"), DecisionOptions.live("order", "ord_w", Actor.user("tester")));
        DecisionTrace t = decisions.why(r.recordId());
        assertThat(t.tier().name()).isEqualTo("T1");
        assertThat(t.questions()).extracting(DecisionTrace.QuestionTrace::key).contains("intent", "split_ok", "is_gift");
        assertThat(t.questions().get(0).choiceCriteria()).containsKey("unclear");
        assertThat(t.questions().get(0).answer().probabilities()).isNotEmpty();
        assertThat(t.bands().actGte()).isEqualTo(0.8);
        assertThat(t.state()).containsKey("order");
        assertThat(t.postChecks()).hasSize(2);
        assertThat(t.actor()).isEqualTo("user:tester");
        assertThat(t.escalation()).isNull();
        assertThat(t.provider()).isEqualTo("heuristic");
        assertThat(t.definition()).containsEntry("owner", "omnichannel-strategy");

        decisions.label(r.recordId(), "correct", Map.of("value", true, "source", "cs_review"));
        assertThat(decisions.record(r.recordId()).labels()).containsKey("correct");
        assertThat(events("decision", r.recordId(), "decision.labelled")).hasSize(1);

        DecisionRecord o = decisions.override(r.recordId(), "ship_complete", "customer confirmed by phone");
        assertThat(o.overriddenValue()).isEqualTo("ship_complete");
        assertThat(o.overrideReason()).isEqualTo("customer confirmed by phone");
        assertThat(o.overriddenBy()).isEqualTo("user:tester");
        assertThat(o.effectiveValue()).isEqualTo("ship_complete");
        assertThat(o.value()).isEqualTo("earliest_partial");
        assertThat(events("decision", r.recordId(), "decision.overridden")).hasSize(1);
        assertThat(jdbc.sql("select count(*) from audit_log where tenant_id = :t and action = 'decision.override' and object_id = :id").param("t", TENANT).param("id", r.recordId()).query(Integer.class).single()).isEqualTo(1);

        CustomerSafeTraceView safe = CustomerSafeTraceView.from(decisions.why(r.recordId()));
        assertThat(safe.value()).isEqualTo("ship_complete");
        assertThat(Json.write(safe)).doesNotContain("margin").doesNotContain("costUsd").doesNotContain("probabilities");

        actAs("cs_rep", Set.of("decision.read"));
        assertThatThrownBy(() -> decisions.override(r.recordId(), "unclear", "no")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void batchDecideAndSimulationNeverEscalates() {
        List<DecisionResult> results = decisions.decideAll(List.of(
            new Invocation("fulfilment_intent", intentInput("collect in store please", "b1"), new DecisionOptions("order", "b1", null, Mode.SIMULATION, null, Actor.system("batch"), Map.of())),
            new Invocation("fulfilment_intent", intentInput("each item as soon as ready", "b2"), new DecisionOptions("order", "b2", null, Mode.SIMULATION, null, Actor.system("batch"), Map.of()))));
        assertThat(results).hasSize(2);
        assertThat(results.get(0).value()).isEqualTo("pickup_consolidated");
        assertThat(results.get(1).value()).isEqualTo("ship_as_available");
        results.forEach(r -> { assertThat(r.escalationId()).isNull(); assertThat(decisions.record(r.recordId()).mode()).isEqualTo(Mode.SIMULATION); });
    }
}
