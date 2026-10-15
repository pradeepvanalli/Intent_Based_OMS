package ai.intentoms.finance.guard;

import ai.intentoms.finance.config.FinanceConfigKeys;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.GuardrailViolationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GR-05 monetary guardrail: velocity caps per tenant, per actor and per agent over a rolling one-hour window, plus
 * a circuit breaker on abnormal aggregate refund / appeasement spend.
 *
 * <p>Call {@link #check} before any refund or appeasement is issued (whether a human, a playbook, an agent or a
 * decision requested it) and {@link #record} once the money has actually moved. {@code check} throws
 * {@link GuardrailViolationException} when:</p>
 * <ul>
 *   <li>the breaker is open ({@code tenant_config guardrail.breaker_open_until} in the future): every spend, even
 *       one a human approved, is refused so the caller routes it to human approval (code details
 *       {@code reason=breaker_open});</li>
 *   <li>recorded tenant spend in the window already exceeds cap × 1.5: the breaker is opened for 15 minutes and the
 *       request refused ({@code reason=breaker_tripped});</li>
 *   <li>spent + amount exceeds the tenant, per-actor or per-agent cap ({@code reason=velocity_cap}).</li>
 * </ul>
 * Details always carry {@code cap}, {@code spent}, {@code amount}, {@code scope} and, when open, {@code breakerOpenUntil}.
 * Caps are denominated in USD; amounts in other currencies are compared at face value (the finance module never
 * converts currencies), which is conservative for weaker currencies and documented in the tenant setup guide.
 */
@Service
public class SpendGuard {
    private static final Logger log = LoggerFactory.getLogger(SpendGuard.class);
    public static final Duration WINDOW = Duration.ofHours(1);
    public static final Duration BREAKER_OPEN_FOR = Duration.ofMinutes(15);

    private final SpendLedger ledger;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final AuditLog audit;
    private final Metrics metrics;

    public SpendGuard(SpendLedger ledger, TenantConfigStore config, TimeSource time, AuditLog audit, Metrics metrics) {
        this.ledger = ledger;
        this.config = config;
        this.time = time;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * Verifies a proposed spend against the breaker and all applicable caps.
     *
     * @param category refund or appeasement
     * @param amount   proposed spend (positive)
     * @param actor    actor reference of the requester ("user:alice", "agent:shopbot", "system:playbook")
     * @param agentId  initiating agent id, or null when no agent is involved
     * @return the tenant-scope verdict (for logging / decision traces) when the spend is allowed
     * @throws GuardrailViolationException when the spend must not proceed automatically
     */
    public SpendVerdict check(SpendCategory category, Money amount, String actor, String agentId) {
        Instant now = time.now();
        Optional<Instant> openUntil = breakerOpenUntil().filter(t -> t.isAfter(now));
        if (openUntil.isPresent()) {
            metrics.count("oms.finance.spend_guard.refused", "category", category.code(), "reason", "breaker_open");
            throw violation("Spend circuit breaker is open until " + openUntil.get() + "; route to human approval",
                "breaker_open", "tenant", null, openUntil.get(), category, amount);
        }
        Instant since = now.minus(WINDOW);
        BigDecimal tenantCap = cap(category.tenantCapKey(), category.defaultTenantCap());
        SpendVerdict tenant = SpendVerdict.evaluate("tenant", tenantCap, ledger.spentSince(category, since), amount.amount());
        if (tenant.tripsBreaker()) {
            Instant until = openBreaker(category, tenant, now);
            metrics.count("oms.finance.spend_guard.refused", "category", category.code(), "reason", "breaker_tripped");
            throw violation("Aggregate " + category.code() + " spend " + tenant.spent() + " exceeds " + SpendVerdict.breakerThreshold(tenantCap)
                + " (cap × " + SpendVerdict.BREAKER_FACTOR + "); circuit breaker opened until " + until, "breaker_tripped", "tenant", tenant, until, category, amount);
        }
        if (!tenant.withinCap()) {
            metrics.count("oms.finance.spend_guard.refused", "category", category.code(), "reason", "velocity_cap", "scope", "tenant");
            throw violation("Tenant " + category.code() + " velocity cap " + tenantCap + "/h exceeded", "velocity_cap", "tenant", tenant, null, category, amount);
        }
        if (actor != null && !actor.isBlank()) {
            SpendVerdict a = SpendVerdict.evaluate("actor", cap(category.actorCapKey(), category.defaultActorCap()), ledger.spentByActorSince(category, actor, since), amount.amount());
            if (!a.withinCap()) {
                metrics.count("oms.finance.spend_guard.refused", "category", category.code(), "reason", "velocity_cap", "scope", "actor");
                throw violation("Actor " + actor + " " + category.code() + " velocity cap " + a.cap() + "/h exceeded", "velocity_cap", "actor", a, null, category, amount);
            }
        }
        if (agentId != null && !agentId.isBlank()) {
            SpendVerdict g = SpendVerdict.evaluate("agent", cap(category.agentCapKey(), category.defaultAgentCap()), ledger.spentByAgentSince(category, agentId, since), amount.amount());
            if (!g.withinCap()) {
                metrics.count("oms.finance.spend_guard.refused", "category", category.code(), "reason", "velocity_cap", "scope", "agent");
                throw violation("Agent " + agentId + " " + category.code() + " velocity cap " + g.cap() + "/h exceeded", "velocity_cap", "agent", g, null, category, amount);
            }
        }
        return tenant;
    }

    /**
     * Records spend that actually happened. Also re-evaluates the breaker so spend that bypassed {@link #check}
     * (an approved refund) still trips it once the aggregate turns abnormal.
     */
    public void record(SpendCategory category, Money amount, String actor, String agentId, String refId) {
        Instant now = time.now();
        ledger.append(category, amount, actor, agentId, refId, now);
        metrics.count("oms.finance.spend", amount.amount().doubleValue(), "category", category.code());
        BigDecimal tenantCap = cap(category.tenantCapKey(), category.defaultTenantCap());
        BigDecimal spent = ledger.spentSince(category, now.minus(WINDOW));
        if (spent.compareTo(SpendVerdict.breakerThreshold(tenantCap)) > 0 && breakerOpenUntil().filter(t -> t.isAfter(now)).isEmpty()) {
            openBreaker(category, SpendVerdict.evaluate("tenant", tenantCap, spent, BigDecimal.ZERO), now);
        }
    }

    /** Whether the breaker is currently open. */
    public boolean breakerOpen() {
        Instant now = time.now();
        return breakerOpenUntil().filter(t -> t.isAfter(now)).isPresent();
    }

    /** Manually closes the breaker (finance controller action, audited). */
    public void closeBreaker(String reason) {
        config.delete(FinanceConfigKeys.BREAKER_OPEN_UNTIL);
        audit.record("spend_guard.breaker_closed", "tenant_config", FinanceConfigKeys.BREAKER_OPEN_UNTIL, reason, Map.of());
    }

    public Optional<Instant> breakerOpenUntil() {
        return config.get(FinanceConfigKeys.BREAKER_OPEN_UNTIL).map(Object::toString).flatMap(s -> {
            try { return Optional.of(Instant.parse(s)); } catch (DateTimeParseException e) { log.warn("Ignoring malformed {}: {}", FinanceConfigKeys.BREAKER_OPEN_UNTIL, s); return Optional.empty(); }
        });
    }

    private Instant openBreaker(SpendCategory category, SpendVerdict v, Instant now) {
        Instant until = now.plus(BREAKER_OPEN_FOR);
        // TenantConfigStore stores JSON values; a String is taken as raw JSON text, so the instant must be JSON-encoded.
        config.set(FinanceConfigKeys.BREAKER_OPEN_UNTIL, Json.write(until.toString()));
        metrics.count("oms.finance.spend_guard.breaker_opened", "category", category.code());
        audit.record("spend_guard.breaker_opened", "tenant_config", FinanceConfigKeys.BREAKER_OPEN_UNTIL,
            "Aggregate " + category.code() + " spend " + v.spent() + " exceeded cap " + v.cap() + " × " + SpendVerdict.BREAKER_FACTOR,
            Map.of("category", category.code(), "cap", v.cap(), "spent", v.spent(), "openUntil", until.toString()));
        log.warn("GR-05 circuit breaker opened for tenant until {}: {} spend {} > cap {} × {}", until, category.code(), v.spent(), v.cap(), SpendVerdict.BREAKER_FACTOR);
        return until;
    }

    private BigDecimal cap(String key, BigDecimal dflt) { return config.getDecimal(key, dflt); }

    private static GuardrailViolationException violation(String message, String reason, String scope, SpendVerdict v, Instant breakerOpenUntil, SpendCategory category, Money amount) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("guardrail", "GR-05");
        d.put("reason", reason);
        d.put("category", category.code());
        d.put("scope", scope);
        d.put("amount", amount.amount());
        d.put("currency", amount.currency());
        if (v != null) { d.put("cap", v.cap()); d.put("spent", v.spent()); d.put("remaining", v.remaining()); }
        if (breakerOpenUntil != null) d.put("breakerOpenUntil", breakerOpenUntil.toString());
        d.put("requiresHumanApproval", true);
        return new GuardrailViolationException(message, d);
    }
}
