package ai.intentoms.copilot.live;

import ai.intentoms.copilot.SeededTestBase;
import ai.intentoms.copilot.change.ChangeKind;
import ai.intentoms.copilot.change.ChangeRequest;
import ai.intentoms.copilot.change.ChangeSet;
import ai.intentoms.copilot.change.ConfigCopilotService;
import ai.intentoms.copilot.change.ProposedChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real round-trip against OpenRouter. Runs only when OPENROUTER_API_KEY is present:
 * {@code OPENROUTER_API_KEY=$(cut -d= -f2 ../.env) mvn -q -pl oms-copilot test -Dtest=LiveCopilotSmokeTest}
 */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = "sk-or-.+")
class LiveCopilotSmokeTest extends SeededTestBase {
    @Autowired ConfigCopilotService copilot;

    @DynamicPropertySource
    static void realEndpoint(DynamicPropertyRegistry r) {
        r.add("oms.copilot.enabled", () -> "true");
        r.add("oms.copilot.api-key", () -> System.getenv("OPENROUTER_API_KEY"));
        r.add("oms.copilot.base-url", () -> "https://openrouter.ai/api/v1");
        r.add("oms.copilot.timeout-ms", () -> "180000");
        r.add("oms.copilot.max-retries", () -> "2");
    }

    @Test
    void realModelProducesAValidatedChangeSet() {
        ChangeSet cs = copilot.propose(new ChangeRequest(
            "Gold customers with a receipt get an instant refund under $250; never split orders containing hazmat items; agent orders over $500 shipping to California need a risk review", null, null));
        System.out.println("LIVE CHANGESET " + cs.id() + ": " + cs.summary());
        for (ProposedChange c : cs.changes()) System.out.println(" - " + c.id() + " " + c.kind() + " " + c.target() + " valid=" + c.validation().ok() + " repaired=" + c.repaired() + " issues=" + c.validation().issues() + " tests=" + c.tests().size());
        System.out.println(" assumptions=" + cs.assumptions() + " questions=" + cs.questions());
        List<ProposedChange> changes = cs.changes();
        assertThat(changes).isNotEmpty();
        assertThat(changes).anyMatch(c -> c.kind().isDecision() && "return_risk".equals(c.target()) && c.after().contains("receipt"));
        boolean hazmatChange = changes.stream().anyMatch(c -> ("fulfilment_intent".equals(c.target()) || "sourcing_node_score".equals(c.target()) || "split_acceptability".equals(c.target())) && c.after().toLowerCase().contains("hazmat"));
        boolean hazmatCovered = (cs.summary() + " " + String.join(" ", cs.assumptions())).toLowerCase().contains("hazmat");   // the model may note the existing fulfilment_intent postCheck already blocks hazmat splits
        assertThat(hazmatChange || hazmatCovered).as("hazmat policy is addressed by a change or an explicit assumption").isTrue();
        assertThat(changes).anyMatch(c -> (c.kind() == ChangeKind.state_model || c.kind().isDecision()) && (c.after().contains("agent") && (c.after().contains("500") || c.after().contains("CA"))));
        List<ProposedChange> invalid = changes.stream().filter(c -> !c.validation().ok()).toList();
        assertThat(invalid).as("all changes pass validation: " + invalid.stream().map(c -> c.target() + " " + c.validation().issues()).toList()).isEmpty();
    }
}
