package ai.intentoms.copilot.digest;

import ai.intentoms.copilot.CopilotTestBase;
import ai.intentoms.copilot.support.LlmStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Margin Guardian: findings are grounded in the deterministic facts; ungrounded findings are dropped; run() stores the digest. */
class DigestIntegrationTest extends CopilotTestBase {
    @Autowired DigestService digest;

    @Test
    void findingsMustCiteFactNumbersAndAreStored() {
        captureOrder("TX");
        captureOrder("CA");
        Map<String, Object> grounded = Map.of("title", "Order volume", "metric", "orders", "value", "2 orders", "delta", "n/a", "evidence", "2 orders were placed in the period.",
            "suggestedPrompt", "Raise the split penalty for marketplace orders to $15");
        Map<String, Object> ungrounded = Map.of("title", "Phantom losses", "metric", "margin", "value", "$9,999", "delta", "+42%", "evidence", "Margin given up was $9,999.",
            "suggestedPrompt", "Set the margin floor to 0.2");
        LlmStubs.stub(LLM, "margin_digest", Map.of("headline", "Two orders, no margin issues yet.", "findings", List.of(grounded, ungrounded)));
        Digest d = digest.run(7);
        assertThat(d.generated()).isTrue();
        assertThat(d.findings()).hasSize(1);
        assertThat(d.findings().get(0).suggestedPrompt()).contains("$15");
        assertThat(d.fallbackReason()).contains("Phantom losses");
        assertThat(d.period()).containsEntry("days", 7);
        assertThat(d.facts()).containsKeys("orders", "splits", "belowFloor", "returnsByTiming", "topExceptions", "decisions", "aiCost", "splitPenaltyUsd", "marginFloor");
        assertThat(digest.latest()).isPresent();
        assertThat(digest.latest().get().headline()).isEqualTo("Two orders, no margin issues yet.");
        assertThat(digest.history(5)).extracting(Digest::id).contains(d.id());
    }
}
