package ai.intentoms.copilot.ops;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-MON-08 safety: only whitelisted templates with typed parameters ever reach the database. */
class OpsQueryCatalogTest {
    final OpsQueryCatalog catalog = new OpsQueryCatalog();

    @BeforeEach void tenant() { TenantContext.set("test"); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void unknownTemplateIsRejected() {
        assertThatThrownBy(() -> catalog.bind("drop_all_tables", Map.of())).isInstanceOf(ValidationException.class).hasMessageContaining("Unknown ops query template");
        assertThatThrownBy(() -> catalog.bind("select * from orders", Map.of())).isInstanceOf(ValidationException.class);
    }

    @Test
    void unsafeParametersAreRejectedAndLimitsCapped() {
        assertThatThrownBy(() -> catalog.bind("orders_by_status", Map.of("channel", "web' or 1=1 --"))).isInstanceOf(ValidationException.class).hasMessageContaining("snake_case");
        assertThatThrownBy(() -> catalog.bind("at_risk_orders", Map.of("destState", "Texas; drop table orders"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> catalog.bind("at_risk_orders", Map.of("nodeId", "DC-NJ; delete"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> catalog.bind("stuck_orders", Map.of("hours", 10_000))).isInstanceOf(ValidationException.class).hasMessageContaining("hours");
        assertThatThrownBy(() -> catalog.bind("stuck_orders", Map.of("hours", "twelve"))).isInstanceOf(ValidationException.class);
        BoundQuery q = catalog.bind("at_risk_orders", Map.of("limit", 5000, "destState", "tx", "orderType", "Ship_From_Store", "hours", 24));
        assertThat(q.parameters()).containsEntry("limit", OpsQueryCatalog.MAX_LIMIT).containsEntry("destState", "TX").containsEntry("orderType", "ship_from_store").containsEntry("tenant", "test");
        assertThat(q.sql()).contains("ad.address->>'state' = :destState").contains("o.order_type = :orderType").doesNotContain("{filters}").doesNotContain(":channel");
    }

    @Test
    void defaultsAreAppliedAndEveryTemplateIsTenantScoped() {
        for (OpsQueryTemplate t : catalog.all()) {
            BoundQuery q = catalog.bind(t.name(), Map.of());
            assertThat(q.sql()).as(t.name()).contains("tenant_id = :tenant").doesNotContain("{filters}");
            assertThat(q.parameters()).containsKey("tenant");
            if (q.sql().contains(":limit")) assertThat(q.parameters()).containsKey("limit");
            if (q.sql().contains(":hours")) assertThat(q.parameters()).containsKey("hours");
        }
    }

    @Test
    void keywordMatcherPicksATemplateWithoutAModel() {
        BoundQuery q = catalog.match("How many ship-from-store orders in Texas are at risk today?");
        assertThat(q.template().name()).isEqualTo("at_risk_orders");
        assertThat(q.parameters()).containsEntry("destState", "TX").containsEntry("orderType", "ship_from_store").containsEntry("hours", 24);
        assertThat(catalog.match("show me the open exceptions in the inbox").template().name()).isEqualTo("open_exceptions");
        assertThat(catalog.match("which lines are backordered").template().name()).isEqualTo("backordered_lines");
        assertThat(catalog.match("orders stuck for more than 12 hours at DC-NJ").parameters()).containsEntry("hours", 12).containsEntry("nodeId", "DC-NJ");
    }

    /** XP-06: without a model, every learning example question (and everyday phrasings of it) reaches its template. */
    @Test
    void keywordMatcherRoutesTheLearningExampleQuestions() {
        for (String name : List.of("learning_proposals_by_status", "learning_recent_promotions", "learner_status", "episode_outcome_coverage", "learned_facts_by_key")) {
            OpsQueryTemplate t = catalog.all().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            assertThat(catalog.match(t.example()).template().name()).as(t.example()).isEqualTo(name);
        }
        assertThat(catalog.match("What did learning promote this month?").template().name()).isEqualTo("learning_recent_promotions");
        assertThat(catalog.match("What is the outcome coverage per decision?").template().name()).isEqualTo("episode_outcome_coverage");
        assertThat(catalog.match("which learning changes were rolled back this week").template().name()).isEqualTo("learning_recent_promotions");
        assertThat(catalog.match("are outcomes missing for any decision").template().name()).isEqualTo("episode_outcome_coverage");
        assertThat(catalog.match("which learners are switched off").template().name()).isEqualTo("learner_status");
        assertThat(catalog.match("what learned values do we have for node reliability").template().name()).isEqualTo("learned_facts_by_key");
    }
}
