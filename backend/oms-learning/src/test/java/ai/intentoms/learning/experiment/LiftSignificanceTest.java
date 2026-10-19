package ai.intentoms.learning.experiment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Defect 29 / XP-01: a lift is only called significant with enough episodes on each side and some spread in each. */
class LiftSignificanceTest {
    private static List<ExperimentMetricsRepository.LiftRow> rows(String arm, int n, IntToDoubleFunction reward) {
        List<ExperimentMetricsRepository.LiftRow> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ExperimentMetricsRepository.LiftRow("d", arm, reward.applyAsDouble(i), Map.of()));
        return out;
    }

    private static Map<String, Object> reward(List<ExperimentMetricsRepository.LiftRow> treated, List<ExperimentMetricsRepository.LiftRow> held) {
        return LiftService.metric("reward", treated, held, ExperimentMetricsRepository.LiftRow::reward, 30);
    }

    @Test
    void fourIdenticalHoldoutValuesAreNotSignificant() {
        Map<String, Object> m = reward(rows(null, 40, i -> 0.8 + (i % 5) * 0.01), rows("holdout", 4, i -> 0.5));
        assertThat(m).containsEntry("significant", false).containsEntry("enoughData", false);
        assertThat((String) m.get("reason")).contains("at least 30");
        assertThat(m.get("lift")).isNotNull(); // the means are still shown
    }

    @Test
    void enoughEpisodesButNoSpreadOnOneSideIsNotSignificant() {
        Map<String, Object> m = reward(rows(null, 40, i -> 0.8 + (i % 5) * 0.01), rows("holdout", 40, i -> 0.5));
        assertThat(m).containsEntry("significant", false).containsEntry("enoughData", false);
        assertThat((String) m.get("reason")).contains("in the holdout is the same");
    }

    @Test
    void enoughEpisodesWithSpreadAndAClearGapIsSignificant() {
        Map<String, Object> m = reward(rows(null, 40, i -> 0.8 + (i % 5) * 0.01), rows("holdout", 40, i -> 0.5 + (i % 4) * 0.01));
        assertThat(m).containsEntry("significant", true).containsEntry("enoughData", true).containsEntry("improvement", true);
        assertThat(m).doesNotContainKey("reason");
    }

    @Test
    void tooFewOnEitherSideShowsNoLift() {
        Map<String, Object> m = reward(rows(null, 1, i -> 0.9), rows("holdout", 40, i -> 0.5 + (i % 4) * 0.01));
        assertThat(m).containsEntry("significant", false).containsEntry("enoughData", false);
        assertThat(m.get("lift")).isNull();
    }
}
