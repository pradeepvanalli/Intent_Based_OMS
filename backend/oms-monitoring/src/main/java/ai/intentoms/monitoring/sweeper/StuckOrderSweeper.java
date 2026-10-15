package ai.intentoms.monitoring.sweeper;

import ai.intentoms.monitoring.config.MonitoringProperties;
import ai.intentoms.monitoring.exception.ExceptionRepository;
import ai.intentoms.monitoring.exception.ExceptionRequest;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.monitoring.sla.SlaDefinitionService;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Optional;

/**
 * Stuck-order sweeper (FR-MON-05): every {@code oms.monitoring.stuck-sweep-ms} it compares, for each open order, the time of
 * the newest business event on the order aggregate (monitoring's own {@code exception.*} / {@code order.risk_scored} events
 * excluded) with the {@code expectedNextEventWithin} window for the order's current status
 * ({@link SlaDefinitionService}). Orders past the window get one {@code stuck} exception per window (source
 * {@code sweeper}, code {@code no_event_within_window}), which starts the {@code diagnose_stuck} playbook.
 */
@Component
public class StuckOrderSweeper {
    public static final String TYPE = "stuck";
    public static final String SOURCE = "sweeper";
    public static final String CODE = "no_event_within_window";
    private static final Logger log = LoggerFactory.getLogger(StuckOrderSweeper.class);

    private final JdbcClient jdbc;
    private final ExceptionService exceptions;
    private final ExceptionRepository exceptionRepo;
    private final SlaDefinitionService slas;
    private final TimeSource time;
    private final Metrics metrics;
    private final MonitoringProperties props;

    public StuckOrderSweeper(JdbcClient jdbc, ExceptionService exceptions, ExceptionRepository exceptionRepo, SlaDefinitionService slas, TimeSource time, Metrics metrics, MonitoringProperties props) {
        this.jdbc = jdbc;
        this.exceptions = exceptions;
        this.exceptionRepo = exceptionRepo;
        this.slas = slas;
        this.time = time;
        this.metrics = metrics;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${oms.monitoring.stuck-sweep-ms:60000}", initialDelayString = "${oms.monitoring.stuck-sweep-ms:60000}")
    public void scheduled() {
        if (!props.isEnabled()) return;
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Stuck-order sweep failed: {}", e.toString()); }
    }

    /** Sweep every tenant with open orders (or only the bound tenant); returns the ids of orders flagged as stuck. */
    public List<String> sweepOnce() {
        List<String> flagged = new ArrayList<>();
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() ->
            jdbc.sql("select distinct tenant_id from orders where status not in (:terminal)").param("terminal", props.terminalStatuses()).query(String.class).list());
        for (String tenant : tenants) {
            try { flagged.addAll(TenantContext.supplyAs(tenant, this::sweepTenant)); }
            catch (RuntimeException e) { log.warn("Stuck-order sweep failed for tenant {}: {}", tenant, e.toString()); metrics.count("oms.monitoring.stuck_sweep_failures"); }
        }
        return flagged;
    }

    private List<String> sweepTenant() {
        Instant now = time.now();
        List<Map<String, Object>> rows = jdbc.sql("""
            select o.id, o.status, o.order_type, o.updated_at, o.placed_at,
                   (select max(ev.occurred_at) from event ev where ev.tenant_id = o.tenant_id and ev.aggregate_type = 'order' and ev.aggregate_id = o.id
                      and ev.type not like 'exception.%' and ev.type <> 'order.risk_scored') as last_event
            from orders o where o.tenant_id = :t and o.status not in (:terminal) order by o.placed_at limit :n
            """).param("t", TenantContext.require()).param("terminal", props.terminalStatuses()).param("n", props.sweepBatch()).query().listOfRows();
        List<String> flagged = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String orderId = (String) r.get("id");
            String status = (String) r.get("status");
            Optional<Duration> window = slas.expectedNextEventWithin((String) r.get("order_type"), status);
            if (window.isEmpty()) continue;
            Instant lastEvent = ts(r.get("last_event") != null ? r.get("last_event") : r.get("updated_at") != null ? r.get("updated_at") : r.get("placed_at"));
            if (lastEvent == null || !lastEvent.plus(window.get()).isBefore(now)) continue;
            if (exceptionRepo.existsSince(orderId, TYPE, now.minus(window.get()))) continue;   // one per order per window
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("state", status);
            details.put("lastEventAt", lastEvent.toString());
            details.put("windowSeconds", window.get().getSeconds());
            details.put("silentMinutes", Duration.between(lastEvent, now).toMinutes());
            String message = "No event on order " + orderId + " in state " + status + " for " + Duration.between(lastEvent, now).toMinutes() + " min (expected within " + window.get() + ")";
            try {
                exceptions.raise(new ExceptionRequest(SOURCE, CODE, message, orderId, null, null, details), TYPE);
                flagged.add(orderId);
                metrics.count("oms.monitoring.stuck_orders", "state", status);
            } catch (RuntimeException e) {
                log.warn("Could not raise stuck exception for {}: {}", orderId, e.toString());
            }
        }
        return flagged;
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
