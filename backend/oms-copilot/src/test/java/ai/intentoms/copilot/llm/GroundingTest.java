package ai.intentoms.copilot.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-XAI-03 / FR-CS-05 post-checks: numbers, dates and amounts in generated text must exist in the source. */
class GroundingTest {
    @Test
    void numbersMustAppearInTheSourceAfterNormalisation() {
        Map<String, Object> trace = Map.of("confidence", 0.86, "version", 2, "value", 4, "latencyMs", 1250);
        assertThat(Grounding.ungroundedNumbers("Version 2 scored 4 with confidence 0.86 after 1,250 ms", trace)).isEmpty();
        assertThat(Grounding.ungroundedNumbers("Version 2 scored 4 with confidence 0.90", trace)).containsExactly("0.9");
        assertThat(Grounding.ungroundedNumbers("confidence 0.860", trace)).isEmpty();   // trailing zeros stripped
        assertThat(Grounding.ungroundedNumbers("no numbers here", trace)).isEmpty();
    }

    @Test
    void datesAndAmountsMustComeFromTheOrderFacts() {
        Map<String, Object> facts = Map.of("promiseDate", "2026-10-09", "refunds", List.of(Map.of("amount", "56.00 USD")), "grandTotal", "94.00 USD");
        assertThat(Grounding.ungroundedDatesAndAmounts("Your parcel arrives on 2026-10-09 and we refunded $56.00.", facts)).isEmpty();
        assertThat(Grounding.ungroundedDatesAndAmounts("Your parcel arrives on 2026-10-12.", facts)).containsExactly("2026-10-12");
        assertThat(Grounding.ungroundedDatesAndAmounts("We will refund $75 as a goodwill gesture.", facts)).containsExactly("$75");
        assertThat(Grounding.ungroundedDatesAndAmounts("Total 94.00 USD", facts)).isEmpty();
    }
}
