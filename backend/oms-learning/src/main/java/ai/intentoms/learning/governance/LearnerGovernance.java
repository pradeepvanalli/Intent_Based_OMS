package ai.intentoms.learning.governance;

import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GV-01 / GV-03: the one place that says whether a learner may run, propose, or feed a live decision right now.
 * Checked in order: the global kill switch ({@code oms.learning.kill-switch}, for an operator who needs every tenant
 * to stop), the tenant's {@code learning.enabled} / {@code learning.kill_switch}, the learner's own on/off switch,
 * and a pause (coverage breach, drift, or a human). A gate is cached for a few seconds so live decisions never hit
 * the database for it; every change made through this class invalidates the cache at once.
 */
@Component
public class LearnerGovernance {
    public static final String TENANT_ENABLED = "learning.enabled";
    public static final String TENANT_KILL_SWITCH = "learning.kill_switch";

    private final LearnerRepository learners;
    private final LearnerRegistry registry;
    private final TenantConfigStore config;
    private final Metrics metrics;
    private final GlobalLearningSwitch global;
    private final Cache<String, Gate> gates = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(10_000).build();

    public LearnerGovernance(LearnerRepository learners, LearnerRegistry registry, TenantConfigStore config, Metrics metrics, GlobalLearningSwitch global) {
        this.learners = learners;
        this.registry = registry;
        this.config = config;
        this.metrics = metrics;
        this.global = global;
    }

    /** {@code mayRun}: may do any work at all. {@code level}: how far its output may go. */
    public record Gate(String learnerKey, boolean mayRun, AutonomyLevel level, String reason) {
        /** L1 and up may draft proposals and raise hypotheses; L0 only observes. */
        public boolean mayPropose() { return mayRun && level != AutonomyLevel.L0_OBSERVE; }
        /** A learned value may reach a live decision only through a promoted live-use change (see ParameterResolver). */
        public boolean mayFeedLive() { return mayRun && level != AutonomyLevel.L0_OBSERVE; }
    }

    public Gate gate(String learnerKey) {
        String tenant = TenantContext.require();
        return gates.get(tenant + "|" + learnerKey, k -> compute(learnerKey));
    }

    /** Gate plus bookkeeping for a learner run: counts skips by reason (NF-09). */
    public Gate begin(String learnerKey) {
        Gate g = gate(learnerKey);
        if (!g.mayRun()) metrics.count("oms.learning.learner.skipped", "learner", learnerKey, "reason", g.reason());
        return g;
    }

    public void recordRun(String learnerKey) {
        learners.recordRun(learnerKey, Instant.now());
        metrics.count("oms.learning.learner.runs", "learner", learnerKey);
    }

    private final java.util.concurrent.ConcurrentHashMap<String, Instant> lastRecorded = new java.util.concurrent.ConcurrentHashMap<>();

    /** For event-driven learners: records a run at most once a minute per tenant and learner, not on every event. */
    public void recordRunThrottled(String learnerKey) {
        Instant now = Instant.now();
        String k = TenantContext.require() + "|" + learnerKey;
        Instant prev = lastRecorded.get(k);
        if (prev != null && prev.plusSeconds(60).isAfter(now)) return;
        lastRecorded.put(k, now);
        recordRun(learnerKey);
    }

    public boolean learningOn() {
        return !global.on() && config.getBoolean(TENANT_ENABLED, true) && !config.getBoolean(TENANT_KILL_SWITCH, false);
    }

    /** Why learning is off for this tenant ("global_kill_switch", "tenant_kill_switch", "tenant_learning_disabled"), or null. */
    public String offReason() {
        if (global.on()) return "global_kill_switch";
        if (!config.getBoolean(TENANT_ENABLED, true)) return "tenant_learning_disabled";
        if (config.getBoolean(TENANT_KILL_SWITCH, false)) return "tenant_kill_switch";
        return null;
    }

    public GlobalLearningSwitch.State globalState() { return global.state(); }

    /** GV-03 global level, platform administrators only (checked by the caller): stops learning for every tenant. */
    public GlobalLearningSwitch.State setGlobalKillSwitch(boolean on, String by) {
        GlobalLearningSwitch.State s = global.set(on, by);
        gates.invalidateAll();
        if (events != null) events.globalKillSwitch(actor(by), Map.of("on", on));
        return s;
    }

    private Gate compute(String learnerKey) {
        registry.ensureRegistered();
        LearnerState s = learners.getOrCreate(learnerKey);
        if (global.on()) return new Gate(learnerKey, false, s.autonomyLevel(), "global_kill_switch");
        if (!config.getBoolean(TENANT_ENABLED, true)) return new Gate(learnerKey, false, s.autonomyLevel(), "tenant_learning_disabled");
        if (config.getBoolean(TENANT_KILL_SWITCH, false)) return new Gate(learnerKey, false, s.autonomyLevel(), "tenant_kill_switch");
        if (!s.enabled()) return new Gate(learnerKey, false, s.autonomyLevel(), "learner_kill_switch");
        if (s.paused()) return new Gate(learnerKey, false, s.autonomyLevel(), "paused: " + s.pausedReason());
        return new Gate(learnerKey, true, s.autonomyLevel(), "ok");
    }

    // ---- changes (all invalidate the cached gates for this tenant and are ledger events, GV-04)

    private ai.intentoms.learning.event.LearningEvents events;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(ai.intentoms.learning.event.LearningEvents events) { this.events = events; }

    public LearnerState setAutonomy(String learnerKey, AutonomyLevel level, String by) {
        AutonomyLevel before = learners.getOrCreate(learnerKey).autonomyLevel();
        learners.setAutonomy(learnerKey, level, by, Instant.now());
        invalidate();
        if (events != null) events.learnerAutonomyChanged(learnerKey, actor(by), Map.of("learner", learnerKey, "from", before.name(), "to", level.name()));
        return learners.getOrCreate(learnerKey);
    }

    /**
     * GV-02: how far one promotion of this learner's proposals may move a number ({@code maxStep}; null clears it, so the
     * default for bounded values applies) and how many promotions per target per week it gets.
     */
    public LearnerState setBudget(String learnerKey, Double maxStep, Integer maxPromotionsPerWeek, String by) {
        LearnerState before = learners.getOrCreate(learnerKey);
        if (maxStep != null && (maxStep <= 0 || maxStep.isNaN())) throw new ai.intentoms.platform.error.ValidationException("maxStep must be a positive number");
        if (maxPromotionsPerWeek != null && maxPromotionsPerWeek < 0) throw new ai.intentoms.platform.error.ValidationException("maxPromotionsPerWeek must be zero or more");
        int perWeek = maxPromotionsPerWeek == null ? before.maxPromotionsPerWeek() : maxPromotionsPerWeek;
        learners.upsert(new LearnerState(learnerKey, before.autonomyLevel(), before.enabled(), perWeek, maxStep, before.lastRunAt(), by, Instant.now(), before.pausedReason()));
        invalidate();
        if (events != null) {
            Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("learner", learnerKey);
            p.put("maxStep", maxStep);
            p.put("maxPromotionsPerWeek", perWeek);
            p.put("previousMaxStep", before.maxStep());
            p.put("previousMaxPromotionsPerWeek", before.maxPromotionsPerWeek());
            events.learnerBudgetChanged(learnerKey, actor(by), p);
        }
        return learners.getOrCreate(learnerKey);
    }

    public LearnerState setEnabled(String learnerKey, boolean enabled, String by) {
        learners.killSwitch(learnerKey, enabled, by, Instant.now());
        invalidate();
        if (events != null) events.learnerSwitched(learnerKey, actor(by), Map.of("learner", learnerKey, "enabled", enabled));
        return learners.getOrCreate(learnerKey);
    }

    /** GV-03: pause or resume all learning for this tenant; live paths fall back to their last stable values. */
    public void setTenantKillSwitch(boolean paused) { setTenantKillSwitch(paused, "system:learning-governance"); }

    public void setTenantKillSwitch(boolean paused, String by) {
        config.set(TENANT_KILL_SWITCH, paused);
        invalidate();
        if (events != null) events.tenantKillSwitch(actor(by), Map.of("paused", paused));
    }

    public void pause(String learnerKey, String reason, String by) {
        learners.pause(learnerKey, reason, by, Instant.now());
        metrics.count("oms.learning.learner.auto_paused", "learner", learnerKey, "reason", reason);
        invalidate();
        if (events != null) events.learnerPaused(learnerKey, actor(by), Map.of("learner", learnerKey, "reason", reason));
    }

    public void resume(String learnerKey, String by) {
        learners.resume(learnerKey, by, Instant.now());
        invalidate();
        if (events != null) events.learnerResumed(learnerKey, actor(by), Map.of("learner", learnerKey));
    }

    private static ai.intentoms.platform.ledger.Actor actor(String by) {
        return ai.intentoms.platform.ledger.Actor.parse(by == null || by.isBlank() ? "system:learning-governance" : by);
    }

    public List<LearnerState> states() {
        registry.ensureRegistered();
        return learners.all();
    }

    public void invalidate() {
        String prefix = TenantContext.require() + "|";
        gates.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }
}
