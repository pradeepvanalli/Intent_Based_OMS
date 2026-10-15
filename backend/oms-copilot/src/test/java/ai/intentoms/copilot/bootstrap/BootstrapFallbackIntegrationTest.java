package ai.intentoms.copilot.bootstrap;

import ai.intentoms.copilot.NoLlmTestBase;
import ai.intentoms.copilot.change.ChangeKind;
import ai.intentoms.copilot.change.ChangeSet;
import ai.intentoms.copilot.change.DraftArtifact;
import ai.intentoms.copilot.change.DraftArtifactService;
import ai.intentoms.copilot.change.ProposedChange;
import ai.intentoms.copilot.cs.CsDraft;
import ai.intentoms.copilot.cs.CsReplyService;
import ai.intentoms.copilot.digest.Digest;
import ai.intentoms.copilot.digest.DigestService;
import ai.intentoms.copilot.explain.ExplainService;
import ai.intentoms.copilot.explain.Explanation;
import ai.intentoms.copilot.ops.OpsAnswer;
import ai.intentoms.copilot.ops.OpsCopilotService;
import ai.intentoms.copilot.api.CopilotStatusService;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.IntentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Without a model key every copilot keeps working deterministically; the bootstrap blueprint is fully staged. */
class BootstrapFallbackIntegrationTest extends NoLlmTestBase {
    @Autowired BootstrapCopilotService bootstrap;
    @Autowired DraftArtifactService artifacts;
    @Autowired DecisionStudioService studio;
    @Autowired OpsCopilotService ops;
    @Autowired ExplainService explain;
    @Autowired CsReplyService cs;
    @Autowired DigestService digest;
    @Autowired IntentService intents;
    @Autowired CopilotStatusService status;

    @Test
    void bootstrapWithoutKeyProducesAStagedBlueprint() {
        BusinessDescription d = new BusinessDescription("We sell fragrances and wine online and through 12 stores; marketplace orders must ship complete.",
            List.of("web", "marketplace", "store"), List.of(Map.of("id", "DC-01", "type", "dc", "state", "NJ")), List.of("fragrance", "wine"), 45, 0.1, true, 0.15, null, null, false, true);
        ChangeSet cs = bootstrap.propose(d);
        assertThat(cs.source()).isEqualTo("bootstrap");
        assertThat(cs.status()).isEqualTo("staged");
        assertThat(cs.meta()).containsEntry("generated", false);
        assertThat(cs.changes()).extracting(ProposedChange::kind).contains(ChangeKind.order_type, ChangeKind.tenant_config, ChangeKind.compliance_rule, ChangeKind.sla, ChangeKind.playbook, ChangeKind.decision_new);
        assertThat(cs.changes()).extracting(ProposedChange::target).contains("standard_ship", "marketplace_ship", "bopis", "return_risk", "fulfilment_intent");
        List<ProposedChange> invalid = cs.changes().stream().filter(c -> !c.validation().ok()).toList();
        assertThat(invalid).as(invalid.stream().map(c -> c.target() + " " + c.validation().issues()).toList().toString()).isEmpty();
        assertThat(cs.changes()).allMatch(c -> c.staged() != null, "every valid change is staged");
        ProposedChange tenantCfg = cs.changes().stream().filter(c -> c.kind() == ChangeKind.tenant_config).findFirst().orElseThrow();
        assertThat(tenantCfg.after()).contains("sourcing.margin_floor: 0.15").contains("returns.window_days: 45").contains("marketplace: ship_complete");
        ProposedChange compliance = cs.changes().stream().filter(c -> c.kind() == ChangeKind.compliance_rule).findFirst().orElseThrow();
        assertThat(compliance.after()).contains("hazmat_no_air").contains("age_restricted_requires_verification").doesNotContain("export_controlled");
        List<DraftArtifact> drafts = artifacts.list("draft", cs.id());
        assertThat(drafts).extracting(DraftArtifact::kind).contains(ChangeKind.order_type, ChangeKind.tenant_config, ChangeKind.compliance_rule, ChangeKind.sla, ChangeKind.playbook);
        ProposedChange rr = cs.changes().stream().filter(c -> "return_risk".equals(c.target())).findFirst().orElseThrow();
        int v = ((Number) rr.staged().get("version")).intValue();
        assertThat(studio.get("return_risk", v).definition().status()).isEqualTo("draft");
        assertThat(cs.meta().get("blueprintSections")).isInstanceOf(Map.class);
        assertThat(status.status()).containsEntry("available", false);
    }

    @Test
    void opsExplainCsAndDigestFallBackDeterministically() {
        Order o = captureOrder("TX", "ship all together please");
        String recordId = intents.inferIntent(o).decisionRecordId();
        OpsAnswer a = ops.ask("How many orders are open in Texas today?");
        assertThat(a.generated()).isFalse();
        assertThat(a.queryName()).isEqualTo("orders_by_status");
        assertThat(a.parameters()).containsEntry("destState", "TX");
        assertThat(a.rowCount()).isGreaterThanOrEqualTo(1);
        assertThat(a.answer()).contains("orders_by_status");
        Explanation e = explain.explain(recordId, "customer");
        assertThat(e.generated()).isFalse();
        assertThat(e.text()).doesNotContain("margin").doesNotContain("confidence");
        CsDraft d = cs.draft(o.id(), "where is my order", null);
        assertThat(d.generated()).isFalse();
        assertThat(d.draft()).contains(o.channelOrderId());
        Digest g = digest.run(7);
        assertThat(g.generated()).isFalse();
        assertThat(g.findings()).hasSizeBetween(3, 6);
        assertThat(g.findings()).allMatch(f -> f.suggestedPrompt() != null && !f.suggestedPrompt().isBlank());
        assertThat(digest.latest()).isPresent();
        assertThat(digest.latest().get().id()).isEqualTo(g.id());
    }
}
