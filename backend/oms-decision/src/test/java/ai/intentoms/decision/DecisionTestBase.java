package ai.intentoms.decision;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.decision.definition.GoldenSetLoader;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Integration test base: Postgres via Testcontainers, demo catalogue and golden sets seeded for tenant "test". */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")   // other modules may share the test database
public abstract class DecisionTestBase extends PostgresTestBase {
    @Autowired protected DecisionDefinitionLoader definitionLoader;
    @Autowired protected GoldenSetLoader goldenLoader;

    @BeforeEach
    void seedCatalogue() {
        definitionLoader.loadDirectory(TestYaml.DEMO_DECISIONS);
        goldenLoader.loadDirectory(TestYaml.DEMO_GOLDEN);
    }

    protected void actAsAgent(String id) {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.AGENT, id, TENANT, Set.of(), Set.of("*"), 1)));
    }

    protected List<Map<String, Object>> events(String aggregateType, String aggregateId, String type) {
        return jdbc.sql("select type, payload::text as payload, metadata::text as metadata from event where tenant_id = :t and aggregate_type = :at and aggregate_id = :aid and type = :type order by sequence")
            .param("t", TENANT).param("at", aggregateType).param("aid", aggregateId).param("type", type).query().listOfRows();
    }

    protected static Map<String, Object> intentInput(String notes, String subjectHint) {
        return Map.of("order", Map.of("channel", "web", "notes", notes, "intent_choices", List.of(), "id", subjectHint, "ship_to", Map.of("name", "Jane Doe", "email", "jane@example.com")),
            "customer", Map.of("tier", "gold", "email", "jane@example.com", "history", Map.of("split_acceptance_rate", 0.2)));
    }
}
