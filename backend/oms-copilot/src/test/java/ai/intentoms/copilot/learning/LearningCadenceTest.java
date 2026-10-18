package ai.intentoms.copilot.learning;

import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.NodeReliabilityLearner;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.platform.ledger.EventSubscriber;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF-02 cadence, checked against the code rather than restated in prose: node and lane posteriors update on the
 * outcome event itself (event subscribers, dispatched by the outbox relay every 250 ms), provisional rewards are
 * recomputed at least every 15 minutes, and the analyst runs nightly. See docs/perf/LEARNING_CADENCE.md.
 */
class LearningCadenceTest {
    private static String placeholderDefault(String expr) {
        int i = expr.lastIndexOf(':');
        return expr.substring(i + 1, expr.length() - 1);
    }

    private static Scheduled scheduled(Class<?> type, String method) throws NoSuchMethodException {
        Method m = type.getMethod(method);
        return m.getAnnotation(Scheduled.class);
    }

    @Test
    void onlineParameterLearnersUpdateOnTheOutcomeEvent() {
        assertThat(EventSubscriber.class).isAssignableFrom(NodeReliabilityLearner.class);
        assertThat(EventSubscriber.class).isAssignableFrom(LaneDeliveryLearner.class);
    }

    @Test
    void provisionalRewardsAreRecomputedWithinFifteenMinutes() throws Exception {
        long ms = Long.parseLong(placeholderDefault(scheduled(RewardMaturityService.class, "scheduled").fixedDelayString()));
        assertThat(Duration.ofMillis(ms)).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void analystRunsNightly() throws Exception {
        CronExpression cron = CronExpression.parse(placeholderDefault(scheduled(AnalystService.class, "scheduled").cron()));
        LocalDateTime first = cron.next(LocalDateTime.of(2026, 1, 5, 12, 0));
        LocalDateTime second = cron.next(first);
        assertThat(Duration.between(first, second)).isEqualTo(Duration.ofDays(1));
        assertThat(first.getHour()).isLessThan(6);
    }
}
