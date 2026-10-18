package ai.intentoms.learning.governance;

import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The single read point for a learned value on a live path (sourcing, promise, inventory, monitoring). Live use of a
 * learned parameter family is itself a promoted change through the EX-07 gate; once promoted, the fast loop's latest
 * value is served, clamped to the promoted range and at most {@code maxStep} away from the static value (GV-02). The
 * static value is served instead whenever there is no learned value yet, the family is not (or no longer) promoted,
 * or its learner is off, paused or at L0 (GV-03: kill switch reverts to the last stable value).
 */
@Component
public class ParameterResolver {
    private final LiveParameterRepository live;
    private final LearnerGovernance governance;
    private final Metrics metrics;
    private final Cache<String, Optional<LiveParameterRepository.LiveParameter>> cache =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(10_000).build();

    public ParameterResolver(LiveParameterRepository live, LearnerGovernance governance, Metrics metrics) {
        this.live = live;
        this.governance = governance;
        this.metrics = metrics;
    }

    /** What was served and why, for the Why drawer and the decision record. {@code value} is null only when both
     * the static and the learned value are absent. */
    public record Resolution(String parameter, Double value, String source, String reason, Double learned, Double staticValue) {
        public boolean learnedServed() { return "LEARNED".equals(source); }
        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("parameter", parameter);
            m.put("value", value);
            m.put("source", source);
            m.put("reason", reason);
            if (learned != null) m.put("learned", learned);
            if (staticValue != null) m.put("static", staticValue);
            return m;
        }
    }

    public double resolve(String parameter, double staticValue, Double learned) {
        return explain(parameter, staticValue, learned).value();
    }

    /** {@code staticValue} may be null for a family with no configured default (the learned value is then additive). */
    public Resolution explain(String parameter, Double staticValue, Double learned) {
        if (learned == null || learned.isNaN()) return fallback(parameter, staticValue, null, "no learned value yet");
        Optional<LiveParameterRepository.LiveParameter> row = liveRow(parameter);
        if (row.isEmpty() || !row.get().live()) return fallback(parameter, staticValue, learned, "not promoted for live use");
        LearnerGovernance.Gate gate = governance.gate(row.get().learnerKey());
        if (!gate.mayFeedLive()) return fallback(parameter, staticValue, learned, gate.mayRun() ? "learner is observe-only (L0)" : gate.reason());
        double v = learned;
        String reason = "promoted for live use";
        LiveParameterRepository.LiveParameter p = row.get();
        if (p.min() != null && v < p.min()) { v = p.min(); reason = "clamped to promoted minimum"; }
        if (p.max() != null && v > p.max()) { v = p.max(); reason = "clamped to promoted maximum"; }
        if (p.maxStep() != null && staticValue != null && Math.abs(v - staticValue) > p.maxStep()) {
            v = staticValue + Math.signum(v - staticValue) * p.maxStep();
            reason = "limited to max step from static value";
        }
        metrics.count("oms.learning.parameter.served", "parameter", parameter, "source", "learned");
        return new Resolution(parameter, v, "LEARNED", reason, learned, staticValue);
    }

    public boolean isLive(String parameter) {
        Optional<LiveParameterRepository.LiveParameter> row = liveRow(parameter);
        return row.isPresent() && row.get().live() && governance.gate(row.get().learnerKey()).mayFeedLive();
    }

    public void promote(String parameter, String learnerKey, Double min, Double max, Double maxStep, String proposalId, String by) {
        live.promote(parameter, learnerKey, min, max, maxStep, proposalId, by, Instant.now());
        invalidate(parameter);
        if (events != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("parameter", parameter);
            p.put("status", "LIVE");
            p.put("learner", learnerKey);
            p.put("min", min);
            p.put("max", max);
            p.put("maxStep", maxStep);
            p.put("proposalId", proposalId);
            events.liveParameterChanged(parameter, ai.intentoms.platform.ledger.Actor.parse(by), p);
        }
    }

    public boolean revoke(String parameter, String by) {
        boolean r = live.revoke(parameter, by, Instant.now());
        invalidate(parameter);
        if (r && events != null) events.liveParameterChanged(parameter, ai.intentoms.platform.ledger.Actor.parse(by), Map.of("parameter", parameter, "status", "REVOKED"));
        return r;
    }

    private ai.intentoms.learning.event.LearningEvents events;

    /** GV-04: live-use promotion and revocation are ledger events. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(ai.intentoms.learning.event.LearningEvents events) { this.events = events; }

    private Resolution fallback(String parameter, Double staticValue, Double learned, String reason) {
        metrics.count("oms.learning.parameter.served", "parameter", parameter, "source", "static");
        return new Resolution(parameter, staticValue, "STATIC", reason, learned, staticValue);
    }

    private Optional<LiveParameterRepository.LiveParameter> liveRow(String parameter) {
        return cache.get(TenantContext.require() + "|" + parameter, k -> live.find(parameter));
    }

    private void invalidate(String parameter) { cache.invalidate(TenantContext.require() + "|" + parameter); }
}
