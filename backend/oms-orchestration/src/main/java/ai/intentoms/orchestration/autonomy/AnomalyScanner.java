package ai.intentoms.orchestration.autonomy;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.monitoring.exception.ExceptionQuery;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anomaly scanner for the Agentic Control Tower (FR-MON-05 sibling): every {@code oms.orchestration.autonomy.scan-ms}
 * it looks, per tenant, for open orders whose SLA breach-risk score is high, that have had no progress within a
 * configurable threshold, whose margin-floor approval has sat open too long, or whose exception backlog item is
 * unresolved past its SLA — exactly the four overlapping signal sources an ops team today cross-references by hand
 * across four screens. Each candidate is diagnosed once with the {@code stuck_order_diagnosis} decision (through the
 * normal {@link DecisionService#decide}, never bypassed) and handed to the {@link AutonomousActionExecutor}.
 *
 * <p>Follows the scheduling pattern of {@code StuckOrderSweeper} / {@code BreachRiskScorer} in oms-monitoring: a
 * {@code @Scheduled} tick fans out over every tenant with open orders via {@code TenantContext.supplyAs}, and
 * {@link #scanOnce()} / {@link #scanTenant()} are also the entry points for {@code POST /scan-now} and tests.</p>
 */
@Component
public class AnomalyScanner {
    private static final Logger log = LoggerFactory.getLogger(AnomalyScanner.class);
    private static final Actor ACTOR = Actor.system("autonomy-scanner");
    private static final List<String> TERMINAL_STATUSES = List.of("delivered", "cancelled", "picked_up", "refunded", "closed");
    private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(60);
    public static final String ANOMALY_NO_PROGRESS = "no_progress";
    public static final String ANOMALY_SLA_BREACH_RISK = "sla_breach_risk";
    public static final String ANOMALY_MARGIN_FLOOR_PENDING = "margin_floor_pending";
    public static final String ANOMALY_EXCEPTION_OVERDUE = "exception_overdue";
    public static final String DECISION_NAME = "stuck_order_diagnosis";

    private final JdbcClient jdbc;
    private final OrderService orders;
    private final DecisionService decisions;
    private final ExceptionService exceptions;
    private final HumanTaskService tasks;
    private final StuckOrderDiagnosisInputBuilder inputBuilder;
    private final AutonomousActionExecutor executor;
    private final AnomalyRepository anomalies;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final Metrics metrics;
    @Value("${oms.orchestration.autonomy.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    public AnomalyScanner(JdbcClient jdbc, OrderService orders, DecisionService decisions, ExceptionService exceptions, HumanTaskService tasks,
                          StuckOrderDiagnosisInputBuilder inputBuilder, AutonomousActionExecutor executor, AnomalyRepository anomalies,
                          TenantConfigStore config, TimeSource time, Metrics metrics) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.decisions = decisions;
        this.exceptions = exceptions;
        this.tasks = tasks;
        this.inputBuilder = inputBuilder;
        this.executor = executor;
        this.anomalies = anomalies;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${oms.orchestration.autonomy.scan-ms:120000}", initialDelayString = "${oms.orchestration.autonomy.scan-ms:120000}")
    public void scheduled() {
        if (!schedulerEnabled) return;
        try { scanOnce(); } catch (RuntimeException e) { log.error("Autonomy scan failed: {}", e.toString()); }
    }

    /** Scans every tenant with open orders (or only the bound tenant); returns the anomalies raised. */
    public List<Anomaly> scanOnce() {
        List<Anomaly> out = new ArrayList<>();
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() ->
            jdbc.sql("select distinct tenant_id from orders where status not in (:terminal)").param("terminal", TERMINAL_STATUSES).query(String.class).list());
        for (String tenant : tenants) {
            try { out.addAll(TenantContext.supplyAs(tenant, this::scanTenant)); }
            catch (RuntimeException e) { log.warn("Autonomy scan failed for tenant {}: {}", tenant, e.toString()); metrics.count("oms.orchestration.autonomy.scan_failures"); }
        }
        return out;
    }

    /** One tenant's scan: gather candidates from all four signal sources, de-duplicated by order id, then diagnose and act. */
    public List<Anomaly> scanTenant() {
        int batch = config.getInt(AutonomyConfigKeys.SCAN_BATCH, AutonomyConfigKeys.DEFAULT_SCAN_BATCH);
        Instant now = time.now();
        Map<String, String> candidates = new LinkedHashMap<>(); // orderId -> anomalyType (first signal wins)
        noProgressCandidates(now, batch).forEach(id -> candidates.putIfAbsent(id, ANOMALY_NO_PROGRESS));
        riskCandidates(batch).forEach(id -> candidates.putIfAbsent(id, ANOMALY_SLA_BREACH_RISK));
        marginFloorCandidates(now).forEach(id -> candidates.putIfAbsent(id, ANOMALY_MARGIN_FLOOR_PENDING));
        exceptionOverdueCandidates(now).forEach(id -> candidates.putIfAbsent(id, ANOMALY_EXCEPTION_OVERDUE));

        List<Anomaly> out = new ArrayList<>();
        for (var e : candidates.entrySet()) {
            if (anomalies.existsRecent(e.getKey(), DEDUPE_WINDOW, now)) continue;
            try { out.add(diagnoseAndAct(e.getKey(), e.getValue())); }
            catch (NotFoundException nf) { log.debug("Autonomy candidate {} vanished before it could be diagnosed: {}", e.getKey(), nf.toString()); }
            catch (RuntimeException ex) { log.warn("Autonomy diagnosis failed for order {}: {}", e.getKey(), ex.toString()); metrics.count("oms.orchestration.autonomy.diagnosis_failures"); }
        }
        return out;
    }

    private Anomaly diagnoseAndAct(String orderId, String anomalyType) {
        Order order = orders.get(orderId);
        Map<String, Object> input = inputBuilder.build(order);
        DecisionResult result = decisions.decide(DECISION_NAME, input, DecisionOptions.live("order", orderId, ACTOR));
        metrics.count("oms.orchestration.autonomy.diagnosed", "anomalyType", anomalyType, "value", String.valueOf(result.value()), "band", result.band().name());
        Anomaly anomaly = executor.resolve(order, anomalyType, result);
        anomalies.insert(anomaly);
        return anomaly;
    }

    // ---- candidate sources ------------------------------------------------------------------------------------

    private List<String> noProgressCandidates(Instant now, int batch) {
        int minutes = config.getInt(AutonomyConfigKeys.STUCK_AFTER_MINUTES, AutonomyConfigKeys.DEFAULT_STUCK_AFTER_MINUTES);
        Timestamp cutoff = Timestamp.from(now.minus(Duration.ofMinutes(minutes)));
        return jdbc.sql("select id from orders where tenant_id = :t and status not in (:terminal) and placed_at < :cutoff order by placed_at limit :n")
            .param("t", TenantContext.require()).param("terminal", TERMINAL_STATUSES).param("cutoff", cutoff).param("n", batch).query(String.class).list();
    }

    /**
     * Reads {@code order_risk} directly (owned by oms-monitoring, documented table shape): oms-orchestration already
     * depends on oms-monitoring's Java API for exceptions and playbooks, but no bulk "orders above a risk score"
     * query is exposed, and adding one there is outside this feature's file scope — a read-only query against a
     * stable, documented table is the lower-footprint choice over introducing a new cross-module method.
     */
    private List<String> riskCandidates(int batch) {
        int threshold = config.getInt(AutonomyConfigKeys.RISK_SCORE_THRESHOLD, AutonomyConfigKeys.DEFAULT_RISK_SCORE_THRESHOLD);
        try {
            return jdbc.sql("select r.order_id from order_risk r join orders o on o.tenant_id = r.tenant_id and o.id = r.order_id "
                    + "where r.tenant_id = :t and r.score >= :score and o.status not in (:terminal) order by r.score desc limit :n")
                .param("t", TenantContext.require()).param("score", threshold).param("terminal", TERMINAL_STATUSES).param("n", batch).query(String.class).list();
        } catch (RuntimeException e) {
            log.debug("order_risk unavailable for risk candidates: {}", e.toString());
            return List.of();
        }
    }

    private List<String> marginFloorCandidates(Instant now) {
        int minutes = config.getInt(AutonomyConfigKeys.MARGIN_APPROVAL_STALE_MINUTES, AutonomyConfigKeys.DEFAULT_MARGIN_APPROVAL_STALE_MINUTES);
        Instant cutoff = now.minus(Duration.ofMinutes(minutes));
        List<String> out = new ArrayList<>();
        for (HumanTask t : tasks.list(TaskQuery.open("margin_floor_approvals")).items()) {
            if ("order".equals(t.subjectType()) && t.createdAt() != null && t.createdAt().isBefore(cutoff)) out.add(t.subjectId());
        }
        return out;
    }

    private List<String> exceptionOverdueCandidates(Instant now) {
        List<String> out = new ArrayList<>();
        // status null = every status (ExceptionQuery ignores null filters); isActive() narrows to OPEN/IN_PROGRESS
        // since ExceptionQuery.open() alone would miss an exception already assigned but still overdue.
        for (ExceptionRecord e : exceptions.list(new ExceptionQuery(null, null, null, null, null, null, 0, 200)).items()) {
            if (e.isActive() && e.orderId() != null && e.slaDueAt() != null && e.slaDueAt().isBefore(now)) out.add(e.orderId());
        }
        return out;
    }
}
