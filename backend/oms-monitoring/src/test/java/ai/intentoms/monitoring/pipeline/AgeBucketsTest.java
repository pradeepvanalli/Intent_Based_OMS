package ai.intentoms.monitoring.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-01 age buckets: boundaries are inclusive at the lower end and agree with the SQL CASE. */
class AgeBucketsTest {
    @Test
    void bucketsFollowTheBoundaries() {
        assertThat(AgeBuckets.bucket(Duration.ZERO)).isEqualTo("<1h");
        assertThat(AgeBuckets.bucket(Duration.ofMinutes(59))).isEqualTo("<1h");
        assertThat(AgeBuckets.bucket(Duration.ofHours(1))).isEqualTo("1-4h");
        assertThat(AgeBuckets.bucket(Duration.ofHours(3).plusMinutes(59))).isEqualTo("1-4h");
        assertThat(AgeBuckets.bucket(Duration.ofHours(4))).isEqualTo("4-24h");
        assertThat(AgeBuckets.bucket(Duration.ofHours(23))).isEqualTo("4-24h");
        assertThat(AgeBuckets.bucket(Duration.ofHours(24))).isEqualTo("1-3d");
        assertThat(AgeBuckets.bucket(Duration.ofHours(71))).isEqualTo("1-3d");
        assertThat(AgeBuckets.bucket(Duration.ofHours(72))).isEqualTo(">3d");
        assertThat(AgeBuckets.bucket(Duration.ofDays(30))).isEqualTo(">3d");
    }

    @Test
    void negativeAgesAndNullsAreYoungest() {
        assertThat(AgeBuckets.bucket(Duration.ofMinutes(-5))).isEqualTo("<1h");
        assertThat(AgeBuckets.bucket((Duration) null)).isEqualTo("<1h");
    }

    @Test
    void orderMatchesUiContractAndSqlCaseUsesSameBoundaries() {
        assertThat(AgeBuckets.ORDER).containsExactly("<1h", "1-4h", "4-24h", "1-3d", ">3d");
        String sql = AgeBuckets.sqlCase("age");
        assertThat(sql).contains("age < 3600 then '<1h'").contains("age < 14400 then '1-4h'").contains("age < 86400 then '4-24h'").contains("age < 259200 then '1-3d'").contains("else '>3d'");
    }
}
