package ai.intentoms.simulation.batch;

import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.model.OrderSummary;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.simulation.config.SimulationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Batch simulation over a date range (FR-SIM-02) with what-if overrides (FR-SIM-03). {@link #submit} persists a QUEUED
 * {@code simulation_job} and returns at once; a virtual thread bound to the submitter's tenant and principal then walks the
 * orders placed in {@code [from, to)} newest first (paginated through {@link OrderRepository#search}), simulates each one
 * with {@link OrderSimulator} and commits progress and partial results every {@code oms.simulation.commit-every} orders so
 * the UI can poll. Cancel requests are honoured at every checkpoint. Metrics: counter {@code oms.simulation.jobs} tagged by
 * terminal status, timer {@code oms.simulation.job}.
 */
@Service
public class BatchSimulationService {
    private static final Logger log = LoggerFactory.getLogger(BatchSimulationService.class);
    public static final String KIND_BATCH = "batch";

    private final SimulationJobRepository jobs;
    private final OrderRepository orders;
    private final OrderSimulator simulator;
    private final SimulationProperties props;
    private final AuthorizationService authz;
    private final TimeSource time;
    private final Metrics metrics;
    private final Map<String, CountDownLatch> inFlight = new ConcurrentHashMap<>();

    public BatchSimulationService(SimulationJobRepository jobs, OrderRepository orders, OrderSimulator simulator, SimulationProperties props, AuthorizationService authz,
                                  TimeSource time, Metrics metrics) {
        this.jobs = jobs;
        this.orders = orders;
        this.simulator = simulator;
        this.props = props;
        this.authz = authz;
        this.time = time;
        this.metrics = metrics;
    }

    public SimulationJob submit(BatchRequest request) { return submit(request, KIND_BATCH); }

    /** Submit with a scenario kind label (node_closure | carrier_outage | tariff_change) for the what-if helpers. */
    public SimulationJob submit(BatchRequest request, String kind) {
        if (request == null || request.from() == null || request.to() == null) throw new ValidationException("from and to are required");
        if (!request.to().isAfter(request.from())) throw new ValidationException("to must be after from", Map.of("from", request.from().toString(), "to", request.to().toString()));
        String tenant = TenantContext.require();
        Principal principal = authz.currentPrincipal().orElseGet(() -> Principal.system(tenant));
        Instant now = time.now();
        SimulationJob job = new SimulationJob(Ids.prefixed("sim"), kind == null || kind.isBlank() ? KIND_BATCH : kind, request.label(), JobStatus.QUEUED, 0.0, request.from(), request.to(),
            request.overrides(), props.sampleLimit(request.sampleLimit()), principal.actorRef(), now, null, null, null, null);
        jobs.insert(job);
        metrics.count("oms.simulation.jobs", "status", "submitted", "kind", job.kind());
        CountDownLatch done = new CountDownLatch(1);
        inFlight.put(job.id(), done);
        Thread.ofVirtual().name("simulation-" + job.id()).start(() -> {
            TenantContext.set(tenant);
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(principal));
            try {
                run(job);
            } finally {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
                done.countDown();
                inFlight.remove(job.id());
            }
        });
        return job;
    }

    public SimulationJob get(String id) {
        return jobs.find(id).orElseThrow(() -> new NotFoundException("Simulation job " + id + " not found", Map.of("id", id)));
    }

    public Page<SimulationJob> list(int offset, int limit) {
        int lim = limit <= 0 || limit > 200 ? 50 : limit;
        int off = Math.max(0, offset);
        return Page.of(jobs.list(off, lim), jobs.count(), off, lim);
    }

    /** Asks a QUEUED/RUNNING job to stop at its next checkpoint; throws {@code ConflictException} when already terminal. */
    public SimulationJob cancel(String id) {
        SimulationJob job = get(id);
        if (job.status().terminal()) throw new ConflictException("Simulation job " + id + " is already " + job.status(), Map.of("id", id, "status", job.status().name()));
        jobs.requestCancel(id);
        return get(id);
    }

    /** Blocks until the job's worker thread has finished (tests and synchronous callers); false on timeout. */
    public boolean await(String id, Duration timeout) {
        CountDownLatch latch = inFlight.get(id);
        if (latch == null) return true;
        try { return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private void run(SimulationJob job) {
        Instant started = time.now();
        jobs.markStarted(job.id(), started);
        SimulationMetrics baseline = new SimulationMetrics(), simulated = new SimulationMetrics();
        List<Map<String, Object>> sample = new ArrayList<>();
        int processed = 0, flips = 0, blocked = 0, offset = 0, failures = 0;
        int limit = job.sampleLimit();
        JobStatus terminal = JobStatus.COMPLETED;
        String error = null;
        try {
            long total = -1;
            while (processed < limit) {
                if (jobs.cancelRequested(job.id())) { terminal = JobStatus.CANCELLED; break; }
                int pageSize = Math.min(props.pageSize(), limit - processed);
                Page<OrderSummary> page = orders.search(new OrderQuery(null, null, null, null, null, job.from(), job.to(), null, null, null, offset, pageSize));
                if (total < 0) total = Math.min(page.total(), limit);
                if (page.items().isEmpty()) break;
                for (OrderSummary o : page.items()) {
                    if (processed >= limit) break;
                    OrderSimulation sim;
                    try {
                        sim = simulator.simulate(o.id(), job.overrides());
                    } catch (RuntimeException e) {
                        failures++;
                        log.warn("Simulation of order {} in job {} failed: {}", o.id(), job.id(), e.getMessage());
                        sim = new OrderSimulation(o.id(), null, null, List.of(), 0, e.getMessage());
                    }
                    baseline.add(sim.baselinePlan(), sim.decisions().size(), sim.baselineTouchless(), sim.baselineEscalations());
                    simulated.add(sim.simulatedPlan(), sim.decisions().size(), sim.simulatedTouchless(), sim.simulatedEscalations());
                    flips += sim.flippedDecisions().size();
                    blocked += sim.blockedTransitions();
                    if (sample.size() < props.perOrderSample()) sample.add(sim.sampleRow());
                    processed++;
                    if (processed % props.commitEvery() == 0) {
                        jobs.progress(job.id(), progress(processed, total), SimulationMetrics.report(baseline.snapshot(), simulated.snapshot(), sample, processed, flips, blocked, true));
                        if (jobs.cancelRequested(job.id())) { terminal = JobStatus.CANCELLED; break; }
                    }
                }
                if (terminal == JobStatus.CANCELLED) break;
                offset += page.items().size();
                if (offset >= page.total()) break;
            }
            Map<String, Object> results = SimulationMetrics.report(baseline.snapshot(), simulated.snapshot(), sample, processed, flips, blocked, terminal != JobStatus.COMPLETED);
            results.put("failedOrders", failures);
            results.put("durationMs", Duration.between(started, time.now()).toMillis());
            jobs.finish(job.id(), terminal, terminal == JobStatus.COMPLETED ? 1.0 : progress(processed, total < 0 ? processed : total), results, null, time.now());
        } catch (RuntimeException e) {
            terminal = JobStatus.FAILED;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Simulation job {} failed after {} orders", job.id(), processed, e);
            Map<String, Object> partial = SimulationMetrics.report(baseline.snapshot(), simulated.snapshot(), sample, processed, flips, blocked, true);
            jobs.finish(job.id(), terminal, progress(processed, Math.max(processed, limit)), partial, error, time.now());
        }
        metrics.count("oms.simulation.jobs", "status", terminal.name().toLowerCase(), "kind", job.kind());
        metrics.record("oms.simulation.job", Duration.between(started, time.now()), "kind", job.kind());
        log.info("Simulation job {} {} ({} orders, {} flips, {} blocked transitions)", job.id(), terminal, processed, flips, blocked);
    }

    private static double progress(int processed, long total) {
        if (total <= 0) return processed == 0 ? 0.0 : 1.0;
        return Math.min(1.0, processed / (double) total);
    }
}
