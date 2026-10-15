package ai.intentoms.finance.guard;

import ai.intentoms.finance.FinanceTestBase;
import ai.intentoms.finance.config.FinanceConfigKeys;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.GuardrailViolationException;
import ai.intentoms.platform.time.TimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GR-05 velocity caps and circuit breaker against the real ledger and config store. */
class SpendGuardIntegrationTest extends FinanceTestBase {
    @Autowired SpendGuard guard;
    @Autowired TenantConfigStore config;
    @Autowired TimeSource time;

    @AfterEach
    void resetClock() { time.reset(); }

    @Test
    void tenantCapRefusesSpendBeyondTheRollingHour() {
        config.set(FinanceConfigKeys.REFUND_CAP_TENANT, 100);
        guard.record(SpendCategory.REFUND, Money.usd("60.00"), "user:alice", null, "ref_1");
        assertThat(guard.check(SpendCategory.REFUND, Money.usd("40.00"), "user:bob", null).withinCap()).isTrue();
        assertThatThrownBy(() -> guard.check(SpendCategory.REFUND, Money.usd("40.01"), "user:bob", null))
            .isInstanceOfSatisfying(GuardrailViolationException.class, e -> {
                assertThat(e.details()).containsEntry("reason", "velocity_cap").containsEntry("scope", "tenant").containsEntry("guardrail", "GR-05");
                assertThat((BigDecimal) e.details().get("cap")).isEqualByComparingTo("100");
                assertThat((BigDecimal) e.details().get("spent")).isEqualByComparingTo("60.00");
            });
        // Spend older than an hour falls out of the window.
        time.override(Clock.fixed(Instant.now().plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        assertThat(guard.check(SpendCategory.REFUND, Money.usd("100.00"), "user:bob", null).spent()).isEqualByComparingTo("0");
    }

    @Test
    void perActorAndPerAgentCapsAreIndependentOfTheTenantCap() {
        config.set(FinanceConfigKeys.REFUND_CAP_TENANT, 10_000);
        config.set(FinanceConfigKeys.REFUND_CAP_PER_ACTOR, 50);
        config.set(FinanceConfigKeys.REFUND_CAP_PER_AGENT, 20);
        guard.record(SpendCategory.REFUND, Money.usd("45.00"), "user:alice", null, "r1");
        guard.record(SpendCategory.REFUND, Money.usd("15.00"), "agent:shopbot", "shopbot", "r2");
        assertThatThrownBy(() -> guard.check(SpendCategory.REFUND, Money.usd("6.00"), "user:alice", null))
            .isInstanceOfSatisfying(GuardrailViolationException.class, e -> assertThat(e.details()).containsEntry("scope", "actor"));
        guard.check(SpendCategory.REFUND, Money.usd("6.00"), "user:carol", null); // another actor is fine
        assertThatThrownBy(() -> guard.check(SpendCategory.REFUND, Money.usd("6.00"), "agent:shopbot", "shopbot"))
            .isInstanceOfSatisfying(GuardrailViolationException.class, e -> assertThat(e.details()).containsEntry("scope", "agent"));
        guard.check(SpendCategory.REFUND, Money.usd("5.00"), "agent:shopbot", "shopbot");
    }

    @Test
    void breakerOpensWhenAggregateSpendExceedsCapTimesOnePointFive() {
        config.set(FinanceConfigKeys.APPEASEMENT_CAP_TENANT, 100);
        // Approved spend bypassing check(): 160 > 150 trips the breaker on record().
        guard.record(SpendCategory.APPEASEMENT, Money.usd("160.00"), "user:supervisor", null, "ap_1");
        assertThat(guard.breakerOpen()).isTrue();
        Instant until = guard.breakerOpenUntil().orElseThrow();
        assertThat(Duration.between(time.now(), until)).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
        // Even a tiny refund in another category is refused while the breaker is open.
        assertThatThrownBy(() -> guard.check(SpendCategory.REFUND, Money.usd("1.00"), "user:alice", null))
            .isInstanceOfSatisfying(GuardrailViolationException.class, e -> {
                assertThat(e.details()).containsEntry("reason", "breaker_open").containsEntry("requiresHumanApproval", true);
                assertThat(e.details().get("breakerOpenUntil")).isEqualTo(until.toString());
            });
        // After 15 minutes the breaker closes by itself.
        time.override(Clock.fixed(until.plusSeconds(1), ZoneOffset.UTC));
        assertThat(guard.breakerOpen()).isFalse();
        guard.check(SpendCategory.REFUND, Money.usd("1.00"), "user:alice", null);
    }

    @Test
    void checkTripsBreakerWhenRecordedSpendAlreadyAbnormal() {
        config.set(FinanceConfigKeys.REFUND_CAP_TENANT, 100);
        // Insert ledger rows directly (simulating spend recorded while the breaker key was manually cleared).
        jdbc.sql("insert into spend_ledger(tenant_id, category, actor, amount, currency, at) values (:t, 'refund', 'user:x', 151, 'USD', now())").param("t", tenant).update();
        assertThatThrownBy(() -> guard.check(SpendCategory.REFUND, Money.usd("1.00"), "user:alice", null))
            .isInstanceOfSatisfying(GuardrailViolationException.class, e -> assertThat(e.details()).containsEntry("reason", "breaker_tripped"));
        assertThat(guard.breakerOpen()).isTrue();
        guard.closeBreaker("ops reviewed");
        assertThat(guard.breakerOpen()).isFalse();
    }
}
