package ai.intentoms.simulation.learning;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.learner.FraudLabels;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FD-04 / audit C-N1 / C-N6: the fraud-label replay compares like with like.
 * <ul>
 *   <li>A ring rule on a new address token matches none of the labelled orders. It used to be reported as "catches 0
 *       of 18 frauds instead of 13", because the logged answers (an earlier model) were compared with today's
 *       heuristic re-simulating the candidate. Now base and candidate are re-run with the same provider and only orders
 *       the change itself turns differ: no change, no fabricated loss.</li>
 *   <li>The ring exists only in recent orders: its confirmed frauds (chargebacks are final at once) are counted on their
 *       own, goods only from the matured cohort, and the report says what it covers.</li>
 *   <li>return_risk and rto_risk use their own 30-day outcome window, not the 120-day chargeback window.</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class, properties = "oms.workflow.enabled=false")
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.flyway.ignore-migration-patterns=*:missing,*:future", "oms.learning.replay.min-cohort=3"})
class FraudLabelReplayLikeForLikeIntegrationTest extends PostgresTestBase {
    @Autowired ProposalEvaluationService evaluations;
    @Autowired DecisionStudioService studio;
    @Autowired DecisionRecordRepository records;
    @Autowired ProposalRepository proposals;
    @Autowired TenantConfigStore config;

    private String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "fd04b-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    @AfterEach
    void back() { TenantContext.set(TENANT); }

    private static String yaml(String decision) {
        return """
            decision: %s
            version: 1
            owner: test
            tier: T2
            provider: heuristic
            default: false
            state:
              include:
                - payment.velocity_1h
                - address.token
                - order.total
            questions:
              risky:
                type: noul
                statement: This payment is likely fraudulent.
            bands:
              act: { confidence_gte: 0.9 }
              confirm: { confidence_gte: 0.5 }
              escalate: { otherwise: true }
            """.formatted(decision);
    }

    private int install(String decision) {
        DecisionDefinition d = studio.createDraft(decision, yaml(decision));
        studio.publish(decision, d.version(), "live", true);
        return d.version();
    }

    /** A logged decision as the live model made it (it held the order when {@code held}) and its confirmed outcome. */
    private void logged(String decision, int version, String token, boolean held, boolean fraud, String kind, Instant at) {
        String id = Ids.prefixed("dr");
        String subject = "ord_" + Ids.shortId();
        Map<String, Object> input = Map.of("payment", Map.of("velocity_1h", 3), "address", Map.of("token", token), "order", Map.of("id", subject, "total", 100));
        Map<String, Object> label = Map.of("confirmed", fraud, "kind", kind, "provisional", false);
        records.insert(new DecisionRecord(id, decision, version, Mode.LIVE, "order", subject, subject, Layer.L1_SYSTEM_ONE, Band.ACT, "act", held, Map.of(), 0.95, "jev", "jev-1",
            "hash_" + id, Map.of(), input, List.of(), List.of(), 40, 0, 0, false, null, "system:test", Map.of(), null, null, null, null, Map.of(FraudLabels.LABEL, label), at,
            Map.of(), List.of("true", "false"), String.valueOf(held), 1.0, version));
    }

    private Proposal draft(String decision, int base, PolicyChange change) {
        Proposal p = new Proposal(Ids.prefixed("prop"), new PolicyChangeset(new ArtifactRef("decision", decision), base, List.of(change), "test", PolicyChangeset.RiskTier.T2), null,
            PolicyChangeset.RiskTier.T2, ProposalStatus.DRAFT, null, null, "system:template-author", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fraudLabels(Proposal p) {
        return (Map<String, Object>) evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport().get("fraudLabels");
    }

    @Test
    void aRingRuleMatchingNoLabelledOrderShowsNoChangeAndCountsTheRingsConfirmedFrauds() {
        int v = install("payment_risk");
        Instant settled = Instant.now().minus(Duration.ofDays(130));
        // The labelled cohort, decided by an earlier model: it held 13 of 18 frauds and 16 good orders.
        for (int i = 0; i < 13; i++) logged("payment_risk", v, "tok_old_" + i, true, true, "chargeback_fraud", settled);
        for (int i = 0; i < 5; i++) logged("payment_risk", v, "tok_old_m" + i, false, true, "chargeback_fraud", settled);
        for (int i = 0; i < 16; i++) logged("payment_risk", v, "tok_old_g" + i, true, false, "chargeback_won", settled);
        for (int i = 0; i < 30; i++) logged("payment_risk", v, "tok_ok_" + i, false, false, "chargeback_won", settled);
        // The new ring: four recent orders to one address, three already charged back as fraud.
        Instant recent = Instant.now().minus(Duration.ofDays(3));
        for (int i = 0; i < 3; i++) logged("payment_risk", v, "tok_ring", false, true, "chargeback_fraud", recent);

        Map<String, Object> fraud = fraudLabels(draft("payment_risk", v, new PolicyChange.RuleAdd("payment_risk", "input.address.token == 'tok_ring'", "true", "address ring")));

        assertThat(fraud).containsEntry("sufficient", true).containsEntry("labelled", 64).containsEntry("frauds", 18).containsEntry("goods", 46)
            .containsEntry("decisionsChanged", 0).containsEntry("verdict", "no_change");
        assertThat(((Number) fraud.get("expectedCostChange")).doubleValue()).isZero();
        assertThat(String.valueOf(fraud.get("summary"))).contains("catches 13 of 18 frauds instead of 13").contains("blocks 16 good orders instead of 16");
        assertThat((Map<String, Object>) fraud.get("recentConfirmedFrauds")).containsEntry("count", 3).containsEntry("caughtProposed", 3).containsEntry("caughtNow", 0);
        assertThat(String.valueOf(fraud.get("coverage"))).contains("64 confirmed labels").contains("decides 0 of them differently")
            .contains("3 confirmed frauds (the proposed policy would stop 3, the current 0)").contains("not settled yet");
    }

    @Test
    void returnAndRtoRiskUseTheirOwnOutcomeWindow() {
        int v = install("rto_risk");
        Instant fortyDaysAgo = Instant.now().minus(Duration.ofDays(40));
        for (int i = 0; i < 12; i++) logged("rto_risk", v, "tok_" + i, i < 4, true, "refused_delivery", fortyDaysAgo);
        for (int i = 0; i < 24; i++) logged("rto_risk", v, "tok_g" + i, false, false, "delivered", fortyDaysAgo);

        Map<String, Object> fraud = fraudLabels(draft("rto_risk", v, new PolicyChange.RuleAdd("rto_risk", "input.address.token == 'tok_5'", "true", "one address")));

        assertThat(fraud).as("decided 40 days ago: outside the 30-day outcome window, so settled").containsEntry("sufficient", true).containsEntry("labelled", 36);
        assertThat((Map<String, Object>) fraud.get("window")).containsEntry("observationWindowDays", 30);
        // The rule turns one order (tok_5, a refused parcel the model let through): it now catches 5 of 12.
        assertThat(fraud).containsEntry("decisionsChanged", 1);
        assertThat(String.valueOf(fraud.get("summary"))).contains("catches 5 of 12 frauds instead of 4");
    }
}
