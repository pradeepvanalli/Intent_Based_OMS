package ai.intentoms.copilot.change;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffTest {
    @Test
    void rendersHunksWithContextAndCountsChanges() {
        String before = "decision: return_risk\nversion: 2\nrules:\n  - id: a\n    when: x\n    then: 1\nquestions:\n  q: {}\nbands: {}\n";
        String after = "decision: return_risk\nversion: 2\nrules:\n  - id: a\n    when: x\n    then: 0\n  - id: b\n    when: y\n    then: 4\nquestions:\n  q: {}\nbands: {}\n";
        String diff = UnifiedDiff.of(before, after, "decision/return_risk");
        assertThat(diff).startsWith("--- decision/return_risk (current)\n+++ decision/return_risk (proposed)\n");
        assertThat(diff).contains("-    then: 1").contains("+    then: 0").contains("+  - id: b").contains("@@ -");
        assertThat(diff).doesNotContain("-decision: return_risk");
        int[] stats = UnifiedDiff.stats(before, after);
        assertThat(stats[0]).isEqualTo(4);
        assertThat(stats[1]).isEqualTo(1);
    }

    @Test
    void newArtefactIsAllAdditions() {
        String diff = UnifiedDiff.of(null, "a\nb\n", "order_type/x");
        assertThat(diff).contains("(new)").contains("+a").contains("+b");
        assertThat(UnifiedDiff.stats(null, "a\nb\n")[0]).isEqualTo(3);
    }
}
