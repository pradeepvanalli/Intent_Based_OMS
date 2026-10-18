package ai.intentoms.monitoring.sla;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Invocation;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.learner.BacklogBreachCurve;
import ai.intentoms.learning.learner.WarnEarlyThresholdLearner;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.monitoring.config.MonitoringProperties;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Predicted breach risk per open order (FR-MON-02): every {@code oms.monitoring.risk-sweep-ms} the scorer builds the
 * {@code sla_breach_risk} input (order state and age, promise slack, node backlog/reject rate/cutoff, carrier on-time
 * rate), evaluates it in batch through the decision fabric (FR-DEC-07) and stores the Score 0-4 in {@code order_risk}
 * with the decision record id for the Why drawer (FR-XAI-01). Levels: >=3 critical, >=2 high, >=1 medium, else low;
 * {@code order.risk_scored} is appended only when the level changes so the timeline stays readable.
 * Node signals come from the sourcing {@code node} table when it exists; otherwise zeros (the decision then leans on
 * order and carrier signals only).
 *
 * <p>Learned inputs: FS-05 the node's backlog-to-breach probability at its current backlog
 * ({@code node.breach_probability}, live only when {@code sourcing.node_backlog_breach} is promoted). CX-03: when a
 * score first reaches the warn-early threshold ({@code monitoring.warn_early_threshold}: the learned value once
 * promoted, else the tenant's {@code cx.warn_early_threshold}, else none), {@code order.delay_warning} is appended and
 * the orchestration layer re-promises and tells the customer before the promise is missed.
 */
@Service
public class BreachRiskScorer implements RiskLookup {
    public static final String DECISION = "sla_breach_risk";
    public static final String CARRIER_ON_TIME_KEY = "carrier.on_time_rate_7d";
    private static final Logger log = LoggerFactory.getLogger(BreachRiskScorer.class);
    private static final Actor ACTOR = Actor.system("risk-scorer");

    private static final com.fasterxml.jackson.core.type.TypeReference<List<String>> STRINGS = new com.fasterxml.jackson.core.type.TypeReference<>() {};
    private final OrderRiskRepository risks;
    private final DecisionService decisions;
    private final EventStore events;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final Metrics metrics;
    private final MonitoringProperties props;
    private final JdbcClient jdbc;
    private volatile Boolean nodeTablePresent;
    private final LearnedFactCache facts;
    private final ParameterResolver parameters;

    public static final String DELAY_WARNING = "order.delay_warning";

    public BreachRiskScorer(OrderRiskRepository risks, DecisionService decisions, EventStore events, TenantConfigStore config, TimeSource time, Metrics metrics,
                            MonitoringProperties props, JdbcClient jdbc, LearnedFactCache facts, ParameterResolver parameters) {
        this.facts = facts;
        this.parameters = parameters;
        this.risks = risks;
        this.decisions = decisions;
        this.events = events;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
        this.props = props;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${oms.monitoring.risk-sweep-ms:60000}", initialDelayString = "${oms.monitoring.risk-sweep-ms:60000}")
    public void scheduled() {
        if (!props.isEnabled()) return;
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Breach-risk sweep failed: {}", e.toString()); }
    }

    /** Scores up to {@code sweepBatch} open orders per tenant (least recently scored first); returns the number scored. */
    public int sweepOnce() {
        int total = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> risks.tenantsWithOpenOrders(props.terminalStatuses()));
        for (String tenant : tenants) {
            try {
                total += TenantContext.supplyAs(tenant, () -> score(risks.openOrdersForScoring(props.terminalStatuses(), null, props.sweepBatch())).size());
            } catch (RuntimeException e) {
                log.warn("Breach-risk sweep failed for tenant {}: {}", tenant, e.toString());
                metrics.count("oms.monitoring.risk_sweep_failures");
            }
        }
        return total;
    }

    /** Score one order now (e.g. after a transition); empty when the order is closed or unknown. */
    public Optional<OrderRisk> scoreNow(String orderId) {
        List<OrderRisk> out = score(risks.openOrdersForScoring(props.terminalStatuses(), orderId, 1));
        return out.isEmpty() ? Optional.empty() : Optional.of(out.get(0));
    }

    @Override
    public Optional<OrderRisk> riskFor(String orderId) { return risks.find(orderId); }

    private List<OrderRisk> score(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        Instant now = time.now();
        double carrierOnTime = config.getDouble(CARRIER_ON_TIME_KEY, props.defaultCarrierOnTimeRate());
        Duration rescore = Duration.ofMinutes(props.riskRescoreMinutes());
        List<Invocation> batch = new ArrayList<>(rows.size());
        List<Map<String, Object>> inputs = new ArrayList<>(rows.size());
        List<Map<String, Object>> decided = new ArrayList<>(rows.size());
        List<String> signatures = new ArrayList<>(rows.size());
        List<OrderRisk> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> input = buildInput(r, now, carrierOnTime);
            String signature = materialSignature(input);
            Instant scoredAt = OrderRiskRepository.ts(r.get("scored_at"));
            boolean unchanged = signature.equals(r.get("prev_signature")) && scoredAt != null && scoredAt.plus(rescore).isAfter(now);
            if (unchanged) {
                // Same material inputs, decided recently: keep the stored risk. Re-deciding every sweep produced a new
                // decision record (and, in the escalate band, a new human task) per order per minute.
                risks.markChecked((String) r.get("id"), now);
                out.add(new OrderRisk((String) r.get("id"), ((Number) r.get("prev_score")).intValue(), (String) r.get("prev_level"),
                    Json.read((String) r.get("prev_reasons"), STRINGS), (String) r.get("prev_record_id"), scoredAt));
                metrics.count("oms.monitoring.risk_skipped_unchanged");
                continue;
            }
            inputs.add(input);
            decided.add(r);
            signatures.add(signature);
            batch.add(new Invocation(DECISION, input, DecisionOptions.live("order", (String) r.get("id"), ACTOR)));
        }
        if (batch.isEmpty()) return out;
        ParameterResolver.Resolution warnAt = warnEarlyThreshold();
        List<DecisionResult> results = metrics.time("oms.monitoring.risk_decide", () -> decisions.decideAll(batch));
        for (int i = 0; i < decided.size(); i++) {
            Map<String, Object> r = decided.get(i);
            DecisionResult res = results.get(i);
            String orderId = (String) r.get("id");
            int score = scoreOf(res);
            RiskLevel level = RiskLevel.of(score);
            OrderRisk risk = new OrderRisk(orderId, score, level.key(), reasons(res, inputs.get(i)), res.recordId(), now);
            risks.upsert(risk, signatures.get(i));
            String prev = (String) r.get("prev_level");
            if (!level.key().equals(prev)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("score", score);
                payload.put("level", level.key());
                payload.put("previousLevel", prev == null ? "" : prev);
                payload.put("reasons", risk.reasons());
                payload.put("decisionRecordId", res.recordId() == null ? "" : res.recordId());
                payload.put("layer", res.layer().name());
                events.append("order", orderId, new NewEvent("order.risk_scored", payload, ACTOR));
                metrics.count("oms.monitoring.risk_level_changed", "level", level.key());
            }
            metrics.count("oms.monitoring.risk_scored", "level", level.key());
            Integer prevScore = r.get("prev_score") instanceof Number ps ? ps.intValue() : null;
            if (crossedWarnEarly(warnAt.value(), prevScore, score)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("score", score);
                payload.put("threshold", warnAt.value());
                payload.put("thresholdSource", warnAt.source());
                payload.put("reasons", risk.reasons());
                payload.put("decisionRecordId", res.recordId() == null ? "" : res.recordId());
                events.append("order", orderId, new NewEvent(DELAY_WARNING, payload, ACTOR).withCorrelation(orderId, res.recordId()));
                metrics.count("oms.monitoring.delay_warning", "source", warnAt.source());
            }
            out.add(risk);
        }
        return out;
    }

    /** CX-03: warn once, when the score first reaches the threshold (not again while it stays above). */
    static boolean crossedWarnEarly(Double threshold, Integer previousScore, int score) {
        if (threshold == null) return false;
        return score >= threshold && (previousScore == null || previousScore < threshold);
    }

    /** The learned warn-early threshold when promoted for live use, else the tenant's static {@code cx.warn_early_threshold}, else none. */
    ParameterResolver.Resolution warnEarlyThreshold() {
        Double staticValue = config.get(WarnEarlyThresholdLearner.STATIC_KEY).map(v -> v instanceof Number n ? n.doubleValue() : Double.valueOf(String.valueOf(v))).orElse(null);
        Double learned = null;
        try {
            learned = facts.active(SubjectType.DECISION, DECISION, FactKey.WARN_EARLY_THRESHOLD)
                .filter(f -> f.value() instanceof TypedValue.NumberValue).map(f -> ((TypedValue.NumberValue) f.value()).value()).orElse(null);
        } catch (RuntimeException e) {
            log.debug("Warn-early threshold fact unavailable: {}", e.toString());
        }
        return parameters.explain(WarnEarlyThresholdLearner.PARAMETER, staticValue, learned);
    }

    /** FS-05: the node's learned breach probability at this backlog, when promoted for live use. */
    Double learnedBreachProbability(String nodeId, double backlogUnits) {
        try {
            var curve = facts.active(SubjectType.NODE, nodeId, FactKey.NODE_BACKLOG_BREACH_CURVE).map(BacklogBreachCurve::parse).orElse(List.of());
            Double p = BacklogBreachCurve.at(curve, backlogUnits).orElse(null);
            if (p == null) return null;
            ParameterResolver.Resolution r = parameters.explain(NodeReliabilityFeatures.BACKLOG_BREACH, null, p);
            return r.learnedServed() ? r.value() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Hash of the inputs with their fast-moving numbers bucketed: hours in state per 4 h, promise slack per 12 h, node
     * backlog per 10 units, rates to 2 decimals. A change inside a bucket is not material; crossing one is.
     */
    @SuppressWarnings("unchecked")
    static String materialSignature(Map<String, Object> input) {
        Map<String, Object> order = new LinkedHashMap<>((Map<String, Object>) input.getOrDefault("order", Map.of()));
        order.computeIfPresent("hours_in_state", (k, v) -> v instanceof Number n ? Math.floor(n.doubleValue() / 4) * 4 : v);
        order.computeIfPresent("promise_hours_remaining", (k, v) -> v instanceof Number n ? Math.floor(n.doubleValue() / 12) * 12 : v);
        Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) input.getOrDefault("node", Map.of()));
        node.computeIfPresent("backlog_units", (k, v) -> v instanceof Number n ? Math.floor(n.doubleValue() / 10) * 10 : v);
        node.computeIfPresent("reject_rate", (k, v) -> v instanceof Number n ? Math.round(n.doubleValue() * 100) / 100.0 : v);
        node.computeIfPresent("minutes_to_cutoff", (k, v) -> v instanceof Number n ? Math.floor(n.doubleValue() / 60) * 60 : v);
        node.computeIfPresent("breach_probability", (k, v) -> v instanceof Number n ? Math.round(n.doubleValue() * 20) / 20.0 : v);
        Map<String, Object> material = new LinkedHashMap<>(input);
        material.put("order", order);
        material.put("node", node);
        return Json.sha256(material);
    }

    /** Score from the decision: the main value, else the {@code breach_risk} answer, else the L0 default (2). */
    /**
     * Score to store. A confident answer (act band) is used as is; when the decision only recommends (confirm band on
     * T1 with no L2 verdict) the deterministic default supplied by the runtime is used, so an unconfident model never
     * marks the whole pipeline at risk (NFR-05 degraded mode keeps the control tower meaningful).
     */
    static int scoreOf(DecisionResult res) {
        double v = Double.NaN;
        Object fallback = res.details() == null ? null : res.details().get("confirmFallback");
        if (!res.acts() && fallback instanceof Map<?, ?> fb && fb.get("defaultValue") instanceof Number d) v = d.doubleValue();
        else if (res.value() instanceof Number n) v = n.doubleValue();
        else if (res.value() instanceof String s) { try { v = Double.parseDouble(s); } catch (NumberFormatException ignored) { } }
        if (Double.isNaN(v)) {
            Answer a = res.answers().get("breach_risk");
            if (a != null) v = a.asDouble();
        }
        if (Double.isNaN(v)) v = 1;
        return (int) Math.max(0, Math.min(4, Math.round(v)));
    }

    Map<String, Object> buildInput(Map<String, Object> r, Instant now, double carrierOnTime) {
        Instant since = OrderRiskRepository.ts(r.get("since"));
        double hoursInState = since == null ? 0 : Math.max(0, Duration.between(since, now).toMinutes()) / 60.0;
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("state", r.get("state"));
        order.put("hours_in_state", round1(hoursInState));
        Object promise = r.get("promise_date");
        if (promise instanceof java.sql.Date d) {
            LocalDate date = d.toLocalDate();
            double remaining = Duration.between(now, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()).toMinutes() / 60.0;
            order.put("promise_hours_remaining", round1(remaining));
        }
        order.put("ship_method", r.get("ship_method") == null ? "standard" : r.get("ship_method"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("order", order);
        input.put("node", nodeSignals((String) r.get("primary_node_id"), now));
        input.put("carrier", Map.of("on_time_rate_7d", carrierOnTime));
        return input;
    }

    /** backlog_units, reject_rate and minutes_to_cutoff from {@code node.definition} when the sourcing table exists; zeros otherwise. */
    Map<String, Object> nodeSignals(String nodeId, Instant now) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("backlog_units", 0);
        node.put("reject_rate", 0.0);
        node.put("minutes_to_cutoff", 0);
        if (nodeId == null) return node;
        if (!nodeTablePresent()) return withBreachProbability(nodeId, node);
        try {
            Optional<String> def = jdbc.sql("select definition::text from node where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", nodeId)
                .query(String.class).optional();
            if (def.isEmpty()) return node;
            Map<String, Object> d = Json.readMap(def.get());
            Object health = d.get("health");
            if (health instanceof Map<?, ?> h) {
                node.put("backlog_units", num(h.get("backlog"), num(h.get("backlog_units"), 0)).intValue());
                node.put("reject_rate", num(h.get("reject_rate"), 0.0).doubleValue());
            }
            Object cutoff = d.get("cutoff");
            if (cutoff != null) {
                ZoneId zone = d.get("timezone") == null ? ZoneOffset.UTC : ZoneId.of(String.valueOf(d.get("timezone")));
                ZonedDateTime local = now.atZone(zone);
                ZonedDateTime cut = local.with(LocalTime.parse(String.valueOf(cutoff)));
                if (cut.isBefore(local)) cut = cut.plusDays(1);
                node.put("minutes_to_cutoff", Duration.between(local, cut).toMinutes());
            }
        } catch (RuntimeException e) {
            log.debug("Node signals unavailable for {}: {}", nodeId, e.toString());
        }
        return withBreachProbability(nodeId, node);
    }

    private Map<String, Object> withBreachProbability(String nodeId, Map<String, Object> node) {
        Double breach = learnedBreachProbability(nodeId, ((Number) node.get("backlog_units")).doubleValue());
        if (breach != null) node.put("breach_probability", Math.round(breach * 10_000) / 10_000d);
        return node;
    }

    private boolean nodeTablePresent() {
        Boolean present = nodeTablePresent;
        if (present == null) {
            try { present = jdbc.sql("select to_regclass('public.node') is not null").query(Boolean.class).single(); }
            catch (RuntimeException e) { present = false; }
            nodeTablePresent = present;
        }
        return present;
    }

    /** Human-readable reasons: decision reason codes plus the signals that drove the score. */
    @SuppressWarnings("unchecked")
    static List<String> reasons(DecisionResult res, Map<String, Object> input) {
        List<String> out = new ArrayList<>(res.reasonCodes());
        Map<String, Object> order = (Map<String, Object>) input.get("order");
        Map<String, Object> node = (Map<String, Object>) input.get("node");
        Map<String, Object> carrier = (Map<String, Object>) input.get("carrier");
        out.add("in state " + order.get("state") + " for " + order.get("hours_in_state") + "h");
        if (order.containsKey("promise_hours_remaining")) {
            double rem = ((Number) order.get("promise_hours_remaining")).doubleValue();
            out.add(rem < 0 ? "promise missed by " + round1(-rem) + "h" : "promise in " + rem + "h");
        }
        if (((Number) node.get("backlog_units")).intValue() > 0) out.add("node backlog " + node.get("backlog_units") + " units");
        if (((Number) node.get("reject_rate")).doubleValue() > 0.05) out.add("node reject rate " + node.get("reject_rate"));
        double onTime = ((Number) carrier.get("on_time_rate_7d")).doubleValue();
        if (onTime < 0.9) out.add("carrier on-time " + onTime);
        if (res.layer() == Layer.DEFAULT) out.add("default score (decision unavailable)");
        else if (res.requiresHuman() || !res.acts()) out.add("recommendation only (band " + res.band() + ")");
        return out;
    }

    private static Number num(Object o, Number dflt) {
        if (o instanceof Number n) return n;
        if (o instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { } }
        return dflt;
    }

    private static double round1(double d) { return Math.round(d * 10) / 10.0; }

    static Timestamp ts(Instant i) { return Timestamp.from(i); }
}
