package ai.intentoms.learning.store;

import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every PolicyChange variant survives a write and a read through Postgres — the type id must be stored. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ProposalRepositoryRoundTripTest extends PostgresTestBase {
    @Autowired ProposalRepository proposals;
    @Autowired JdbcClient jdbc;

    @Test
    void everyChangeVariantRoundTrips() {
        List<PolicyChange> changes = List.of(
            new PolicyChange.ThresholdChange("exception_classify", "act", 0.5, 0.55),
            new PolicyChange.WeightChange("sourcing.weights", "cost", 0.4, 0.35),
            new PolicyChange.RuleAdd("return_risk", "return.value_usd > 500", "4", "high value"),
            new PolicyChange.RuleRetire("return_risk", "cheap_item_keep_it", "never fires"),
            new PolicyChange.ParameterChange("inventory.reservation_ttl_minutes", 30, 45),
            new PolicyChange.AllowListChange("autonomy.allowed_actions", "re_promise", true));
        String id = Ids.prefixed("prop");
        Instant now = Instant.now();
        proposals.insert(new Proposal(id, new PolicyChangeset(new ArtifactRef("decision", "exception_classify"), 1, changes, null, PolicyChangeset.RiskTier.T1),
            null, PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, Map.of(), "test", now, now, null, null, null, null, null, null, null, null));

        String stored = jdbc.sql("select changeset::text from proposal where proposal_id = :id").param("id", id).query(String.class).single();
        assertThat(stored.replace(" ", "")).contains("\"type\":\"ThresholdChange\"", "\"type\":\"AllowListChange\"");

        Proposal back = proposals.get(id);
        assertThat(back.changeset().changes()).containsExactlyElementsOf(changes);
        assertThat(proposals.byStatus("DRAFT", 50)).extracting(Proposal::proposalId).contains(id);
    }
}
