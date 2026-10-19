package ai.intentoms.simulation.learning;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.hooks.PromotedProviderRouter;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EX-01: replay covers every change type on decision targets, and is explicit when a config or parameter target has no counterfactual. */
@SpringBootTest(classes = TestApplication.class, properties = "oms.workflow.enabled=false")
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.flyway.ignore-migration-patterns=*:missing,*:future", "oms.learning.replay.min-cohort=3"})
class ProposalReplayIntegrationTest extends PostgresTestBase {
    @Autowired ProposalEvaluationService evaluations;
    @Autowired DecisionStudioService studio;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired DecisionService decisions;
    @Autowired DecisionRecordRepository records;
    @Autowired EpisodeRepository episodes;
    @Autowired ProposalRepository proposals;
    @Autowired ai.intentoms.platform.config.TenantConfigStore config;

    static String yaml(String name) {
        return """
            decision: %s
            version: 1
            owner: test
            tier: T1
            provider: heuristic
            default: review
            state:
              include:
                - order.channel
                - order.value
            questions:
              action:
                type: choice
                instructions: Should this order be approved or reviewed?
                criteria:
                  approve: Approve the order, it looks normal
                  review: Send the order to a person for review
                  unclear: Not enough information to tell
            rules:
              - id: big_order
                when: "input.order.value > 1000"
                then: review
                reason: big_order
                priority: 10
            bands:
              act: { confidence_gte: 0.5 }
              confirm: { confidence_gte: 0.3 }
              escalate: { otherwise: true }
            """.formatted(name);
    }

    private String installWithHistory(int n) {
        String name = "f4_replay_" + Ids.shortId().toLowerCase();
        DecisionDefinition d = studio.createDraft(name, yaml(name));
        studio.publish(name, d.version(), "live", true);
        for (int i = 0; i < n; i++) {
            String id = "ord_" + i + Ids.shortId();
            DecisionResult r = decisions.decide(name, Map.of("order", Map.of("id", id, "channel", "web", "value", 100 + i)),
                new DecisionOptions("order", id, id, Mode.LIVE, null, Actor.system("test"), Map.of()));
            DecisionRecord rec = records.find(r.recordId()).orElseThrow();
            Episode e = Episode.open(Ids.prefixed("epi"), rec.id(), name, id, null, null, rec.version(), "order", id, rec.createdAt());
            episodes.upsertDecisionPoint(e);
            episodes.updateStatusAndReward(e.episodeId(), EpisodeStatus.PROVISIONAL, 0.5 + (i % 5) * 0.1, Map.of("otd", 1.0), null);
        }
        return name;
    }

    private Proposal draft(ArtifactRef target, int base, PolicyChange... changes) {
        Proposal p = new Proposal(Ids.prefixed("prop"), new PolicyChangeset(target, base, List.of(changes), "test", PolicyChangeset.RiskTier.T1), null,
            PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "system:author", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    @Test
    void ruleChangesAreResimulatedThroughTheCandidateDefinition() {
        String name = installWithHistory(25);
        int v = definitions.byStatus(name, "live").get(0).definition().version();
        Proposal p = draft(new ArtifactRef("decision", name), v, new PolicyChange.RuleAdd(name, "input.order.value > 50", "approve", "fast_lane"));

        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();

        assertThat(report).containsEntry("method", "resimulation+ips_doubly_robust").containsKeys("liftEstimate", "confidenceInterval");
        assertThat(((Number) report.get("replayed")).intValue()).isEqualTo(25);
        assertThat(((Number) report.get("decisionsChanged")).intValue()).isEqualTo(25); // the new rule decides every one of them
        assertThat(report.get("changeTypes")).isEqualTo(List.of("RuleAdd"));
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.EVALUATED);
    }

    @Test
    void retiringARuleAndChangingWeightsOrProviderAreReplayedToo() {
        String name = installWithHistory(10);
        int v = definitions.byStatus(name, "live").get(0).definition().version();
        Proposal p = draft(new ArtifactRef("decision", name), v, new PolicyChange.RuleRetire(name, "big_order", "unused"),
            new PolicyChange.ParameterChange("fallbackProvider", null, "heuristic"));
        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();
        assertThat(report.get("method")).isEqualTo("resimulation+ips_doubly_robust");
        assertThat(((Number) report.get("decisionsChanged")).intValue()).isZero(); // no logged decision was a big order
    }

    @Test
    void thresholdOnlyChangesUseTheLoggedConfidence() {
        String name = installWithHistory(10);
        int v = definitions.byStatus(name, "live").get(0).definition().version();
        Proposal p = draft(new ArtifactRef("decision", name), v, new PolicyChange.ThresholdChange(name, "act", 0.5, 0.99));
        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();
        assertThat(report.get("method")).isEqualTo("ips_doubly_robust");
        assertThat(((Number) report.get("sampleSize")).intValue()).isEqualTo(10);
        // Defect 3: the plain IPS estimate is reported next to the doubly robust one, with how much of the reward is final.
        assertThat(report).containsKeys("ips", "estimators", "rewards");
        @SuppressWarnings("unchecked") Map<String, Object> ips = (Map<String, Object>) report.get("ips");
        assertThat(ips).containsKeys("proposedRewardEstimate", "liftEstimate", "confidenceInterval");
        @SuppressWarnings("unchecked") Map<String, Object> rewards = (Map<String, Object>) report.get("rewards");
        assertThat(((Number) rewards.get("mature")).intValue() + ((Number) rewards.get("provisional")).intValue()).isEqualTo(10);
        assertThat(rewards).containsKey("matureShare");

        // Re-testing replaces the replay but keeps a shadow verdict already on the report.
        proposals.mergeReport(p.proposalId(), "shadow", Map.of("passed", true, "paired", 12));
        Map<String, Object> again = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();
        assertThat(again).containsKey("shadow").containsEntry("method", "ips_doubly_robust");

        // Defect 2: a live change is not re-tested.
        proposals.approve(p.proposalId(), "user:reviewer", Instant.now());
        proposals.promote(p.proposalId(), Instant.now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluations.evaluate(p.proposalId(), "user:tester"))
            .isInstanceOf(ai.intentoms.platform.error.ConflictException.class).hasMessageContaining("it is live");
    }

    private void learnedSomething(String learner) {
        jdbc.sql("""
            insert into learned_fact(fact_id, tenant_id, subject_type, subject_id, fact_key, value, confidence, sample_size, learner, version, valid_from, created_at)
            values (:id, :t, 'SEGMENT', :s, 'PROMISE_BUFFER_DAYS', '{"type":"NumberValue","value":1.0}'::jsonb, 0.6, 40, :l, 1, now(), now())
            """).param("id", ai.intentoms.platform.ids.Ids.prefixed("fact")).param("t", TENANT).param("s", "test:" + learner).param("l", learner).update();
    }

    @Test
    void configAndParameterTargetsWithoutACounterfactualSaySoInsteadOfInventingLift() {
        String name = installWithHistory(5);
        Proposal route = draft(new ArtifactRef("config", PromotedProviderRouter.KEY_PREFIX + name), 0,
            new PolicyChange.ParameterChange(PromotedProviderRouter.KEY_PREFIX + name, "heuristic", "jev"));
        Map<String, Object> r1 = evaluations.evaluate(route.proposalId(), "user:tester").evaluationReport();
        assertThat(r1.get("method")).isEqualTo("no_counterfactual");
        assertThat(r1).doesNotContainKey("liftEstimate");

        // The learners behind these families have learned something, so each reason names the next thing missing.
        for (String learner : List.of("node_reliability", "promise_buffer", "confidence_calibrator")) learnedSomething(learner);
        Proposal live = draft(new ArtifactRef("parameter", "live:sourcing.node_reject_rate"), 0,
            new PolicyChange.ParameterChange("sourcing.node_reject_rate", Map.of("enabled", false), Map.of("enabled", true)));
        Map<String, Object> r2 = evaluations.evaluate(live.proposalId(), "user:tester").evaluationReport();
        assertThat(r2.get("method")).isEqualTo("no_counterfactual");
        assertThat(String.valueOf(r2.get("reason"))).contains("sourcing_node_score: only").contains("decisions that used a learned value");

        // N6: the reason names what is actually missing. promise_buffer learns promise.buffer_days and declares no outcome
        // decision of its own; the family is read by sla_breach_risk, which has no rewarded decisions on either value yet.
        Proposal buffer = draft(new ArtifactRef("parameter", "live:promise.buffer_days"), 0,
            new PolicyChange.ParameterChange("live:promise.buffer_days", Map.of("enabled", false), Map.of("enabled", true, "min", 0.0, "max", 3.0)));
        String bufferReason = String.valueOf(evaluations.evaluate(buffer.proposalId(), "user:tester").evaluationReport().get("reason"));
        assertThat(bufferReason).doesNotContain("no learner").contains("sla_breach_risk: only ").contains("decisions that used a learned value");
        Proposal curve = draft(new ArtifactRef("parameter", "live:decision.calibration"), 0,
            new PolicyChange.ParameterChange("live:decision.calibration", Map.of("enabled", false), Map.of("enabled", true)));
        assertThat(String.valueOf(evaluations.evaluate(curve.proposalId(), "user:tester").evaluationReport().get("reason")))
            .isEqualTo("confidence_calibrator learns decision.calibration, but no decision that reads it records outcomes, so served and static values cannot be compared");
        // B3-5: an inventory number changes ATP or a cart's hold time; no decision reads it, so there is no decision whose
        // outcomes could show its effect, and the report says so instead of comparing unrelated sourcing_node_score records.
        for (String[] f : new String[][]{{"inventory.inbound_reliability", "inventory_inbound_reliability"}, {"inventory.reservation_ttl", "inventory_reservation_ttl"},
                {"inventory.safety_stock", "inventory_safety_stock"}}) {
            learnedSomething(f[1]);
            Proposal inv = draft(new ArtifactRef("parameter", "live:" + f[0]), 0,
                new PolicyChange.ParameterChange("live:" + f[0], Map.of("enabled", false), Map.of("enabled", true, "learner", f[1])));
            assertThat(String.valueOf(evaluations.evaluate(inv.proposalId(), "user:tester").evaluationReport().get("reason")))
                .isEqualTo(f[1] + " learns " + f[0] + ", but no decision that reads it records outcomes, so served and static values cannot be compared");
        }
        Proposal unknown = draft(new ArtifactRef("parameter", "live:test.nobody_learns_this"), 0,
            new PolicyChange.ParameterChange("live:test.nobody_learns_this", Map.of("enabled", false), Map.of("enabled", true)));
        assertThat(String.valueOf(evaluations.evaluate(unknown.proposalId(), "user:tester").evaluationReport().get("reason"))).startsWith("no learner learns test.nobody_learns_this");

        Proposal weights = draft(new ArtifactRef("config", "sourcing.weights"), 0, new PolicyChange.WeightChange("sourcing.weights", "cost", 0.5, 0.4));
        assertThat(evaluations.evaluate(weights.proposalId(), "user:tester").evaluationReport().get("method")).isEqualTo("no_counterfactual");
    }

    /**
     * FD-04 as the audit reproduced it: an abuse rule (RuleAdd on a fraud decision) must be tested on the decision's
     * labelled fraud history, not only on episode reward. Only confirmed labels count, from one window for goods and frauds.
     */
    @Test
    void anAbuseRuleIsEvaluatedOnLabelledFraudHistory() {
        String tenant = "fd04-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        ai.intentoms.platform.tenant.TenantContext.set(tenant);
        try {
            config.set(ai.intentoms.learning.learner.FraudLabels.WINDOW_KEY, 5);
            String yaml = """
                decision: address_risk
                version: 1
                owner: test
                tier: T2
                provider: heuristic
                default: false
                rules:
                  - id: few_customers_at_address
                    when: "input.payment.address_reuse_count < 3"
                    then: false
                    reason: few_customers_at_address
                    priority: 50
                state:
                  include:
                    - payment.address_reuse_count
                    - order.total
                questions:
                  risky:
                    type: noul
                    statement: This order's address is being abused.
                    criteria:
                      "true": Many customers share the address
                      "false": The address looks normal
                bands:
                  act: { confidence_gte: 0.9 }
                  confirm: { confidence_gte: 0.5 }
                  escalate: { otherwise: true }
                """;
            DecisionDefinition d = studio.createDraft("address_risk", yaml);
            studio.publish("address_risk", d.version(), "live", true);
            int v = d.version();
            Instant settled = Instant.now().minus(java.time.Duration.ofDays(10));
            for (int i = 0; i < 10; i++) labelled(v, 4, true, false, settled);   // a ring the current policy lets through
            for (int i = 0; i < 25; i++) labelled(v, 1, false, false, settled);  // ordinary goods
            for (int i = 0; i < 3; i++) labelled(v, 5, false, false, settled);   // goods the new rule would block
            // Inside the chargeback window: confirmed frauds of any age must not join goods that are not settled yet.
            for (int i = 0; i < 5; i++) labelled(v, 4, true, false, Instant.now().minus(java.time.Duration.ofDays(2)));
            for (int i = 0; i < 5; i++) labelled(v, 1, false, true, Instant.now().minus(java.time.Duration.ofDays(2)));

            Proposal p = draft(new ArtifactRef("decision", "address_risk"), v, new PolicyChange.RuleAdd("address_risk", "input.payment.address_reuse_count >= 3", "true", "address ring"));
            @SuppressWarnings("unchecked")
            Map<String, Object> fraud = (Map<String, Object>) evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport().get("fraudLabels");
            assertThat(fraud).containsEntry("method", "fraud_label_replay").containsEntry("sufficient", true)
                .containsEntry("labelled", 38).containsEntry("frauds", 10).containsEntry("goods", 28).containsEntry("verdict", "lower_cost");
            @SuppressWarnings("unchecked") Map<String, Object> current = (Map<String, Object>) fraud.get("current");
            @SuppressWarnings("unchecked") Map<String, Object> proposed = (Map<String, Object>) fraud.get("proposed");
            assertThat(current).containsEntry("fraudCaught", 0).containsEntry("goodBlocked", 0).containsEntry("recall", 0.0).containsEntry("expectedCost", 1000.0);
            assertThat(proposed).containsEntry("fraudCaught", 10).containsEntry("goodBlocked", 3).containsEntry("precision", 0.7692)
                .containsEntry("recall", 1.0).containsEntry("falsePositiveRate", 0.1071).containsEntry("fraudLoss", 0.0).containsEntry("blockingCost", 75.0);
            assertThat(fraud.get("expectedCostChange")).isEqualTo(-925.0);
        } finally {
            ai.intentoms.platform.tenant.TenantContext.set(TENANT);
        }
    }

    private void labelled(int version, int addressReuse, boolean fraud, boolean provisional, Instant at) {
        String id = Ids.prefixed("dr");
        String subject = "ord_" + Ids.shortId();
        Map<String, Object> input = Map.of("payment", Map.of("address_reuse_count", addressReuse), "order", Map.of("id", subject, "total", 100));
        Map<String, Object> label = Map.of("confirmed", fraud, "kind", fraud ? "chargeback_fraud" : "delivered_no_chargeback", "provisional", provisional);
        records.insert(new DecisionRecord(id, "address_risk", version, Mode.LIVE, "order", subject, subject, ai.intentoms.decision.model.Layer.L1_SYSTEM_ONE,
            ai.intentoms.decision.model.Band.ACT, "act", false, Map.of(), 0.95, "heuristic", "test", "hash_" + id, Map.of(), input, List.of(), List.of(), 40, 0, 0, false,
            null, "system:test", Map.of(), null, null, null, null, Map.of("fraud_outcome", label), at, Map.of(), List.of("true", "false"), "false", 1.0, version));
    }

    /** §7: a change that breaches a hard constraint is refused before evaluation, with the constraint named. */
    @Test
    void hardConstraintViolationsAreRefusedBeforeEvaluation() {
        Proposal floor = draft(new ArtifactRef("config", "sourcing.margin_floor"), 0, new PolicyChange.ParameterChange("sourcing.margin_floor", 0.12, -0.5));
        Proposal f = evaluations.evaluate(floor.proposalId(), "user:tester");
        assertThat(f.status()).isEqualTo(ProposalStatus.REJECTED);
        assertThat(f.rejectedReason()).startsWith("Hard constraint HC-1");

        Proposal compliance = draft(new ArtifactRef("decision", "payment_risk"), 1, new PolicyChange.RuleRetire("payment_risk", "sanctions_screening", "never fires"));
        assertThat(evaluations.evaluate(compliance.proposalId(), "user:tester").rejectedReason()).startsWith("Hard constraint HC-2");

        Proposal refund = draft(new ArtifactRef("config", "autonomy.allowed_actions"), 0, new PolicyChange.AllowListChange("autonomy.allowed_actions", "refund", true));
        assertThat(evaluations.evaluate(refund.proposalId(), "user:tester").rejectedReason()).startsWith("Hard constraint HC-3");

        Proposal cap = draft(new ArtifactRef("config", "autonomy.max_refund_usd_per_day"), 0, new PolicyChange.ParameterChange("autonomy.max_refund_usd_per_day", 200, 5000));
        assertThat(evaluations.evaluate(cap.proposalId(), "user:tester").rejectedReason()).startsWith("Hard constraint HC-4");

        Proposal capacity = draft(new ArtifactRef("config", "node.DC-NJ.capacity_units"), 0, new PolicyChange.ParameterChange("node.DC-NJ.capacity_units", 1000, 1500));
        assertThat(evaluations.evaluate(capacity.proposalId(), "user:tester").rejectedReason()).startsWith("Hard constraint HC-5");

        Proposal protectedRule = draft(new ArtifactRef("decision", "payment_risk"), 1, new PolicyChange.RuleAdd("payment_risk", "input.customer.gender == 'f'", "true", "x"));
        assertThat(evaluations.evaluate(protectedRule.proposalId(), "user:tester").rejectedReason()).startsWith("Hard constraint HC-6");
    }
}
