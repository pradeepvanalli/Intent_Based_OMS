package ai.intentoms.simulation.margin;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.simulation.batch.JobStatus;
import ai.intentoms.simulation.batch.SimulationJob;
import ai.intentoms.simulation.batch.SimulationJobRepository;
import ai.intentoms.simulation.config.SimulationProperties;
import ai.intentoms.simulation.whatif.ScenarioOverrides;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Margin Replay: a retailer drops a CSV export of real orders and gets, as a {@code simulation_job} of kind {@code import},
 * a report of what Intent OMS would have done differently and how much margin it would have recovered. The file is parsed
 * synchronously (so layout problems surface as 422s), then a virtual thread bound to the submitter's tenant and principal
 * replays every order through {@link ReplayPlanner} and commits progress every {@code oms.simulation.commit-every} orders.
 * Nothing from the file is persisted as an order.
 */
@Service
public class MarginReplayService {
    private static final Logger log = LoggerFactory.getLogger(MarginReplayService.class);
    public static final String KIND = "import";
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ORDERS = 5_000;
    public static final int MAX_ROWS = 100_000;
    public static final String SAMPLE_RESOURCE = "replay/shopify-orders-sample.csv";
    public static final String SAMPLE_NAME = "shopify-orders-sample.csv";
    static final int RUNNING_ROW_CAP = 50;

    private final SimulationJobRepository jobs;
    private final ReplayPlanner planner;
    private final SimulationProperties props;
    private final AuthorizationService authz;
    private final TimeSource time;
    private final Metrics metrics;
    private final Map<String, CountDownLatch> inFlight = new ConcurrentHashMap<>();

    public MarginReplayService(SimulationJobRepository jobs, ReplayPlanner planner, SimulationProperties props, AuthorizationService authz, TimeSource time, Metrics metrics) {
        this.jobs = jobs;
        this.planner = planner;
        this.props = props;
        this.authz = authz;
        this.time = time;
        this.metrics = metrics;
    }

    /** Parses the upload and queues the replay; {@code ValidationException} for an unreadable, oversized or over-long file. */
    public SimulationJob submit(String fileName, byte[] bytes, MarginReplayOptions options) {
        if (bytes == null || bytes.length == 0) throw new ValidationException("The uploaded file is empty");
        if (bytes.length > MAX_BYTES) throw new ValidationException("The file is " + (bytes.length / (1024 * 1024)) + " MB; the limit is 10 MB", Map.of("bytes", bytes.length, "max", MAX_BYTES));
        MarginReplayOptions opts = options == null ? MarginReplayOptions.defaults() : options;
        String text = new String(bytes, StandardCharsets.UTF_8);
        OrderCsvParser.Parsed parsed = OrderCsvParser.parse(text, opts.format(), MAX_ORDERS, MAX_ROWS);
        if (parsed.orders().isEmpty()) throw new ValidationException("No orders could be read from the file" + (parsed.skipped().isEmpty() ? "" : " (" + ReplayFindings.describeSkips(countSkips(parsed)) + ")"),
            Map.of("rows", parsed.rows(), "skipped", parsed.skipped().size()));

        String tenant = TenantContext.require();
        Principal principal = authz.currentPrincipal().orElseGet(() -> Principal.system(tenant));
        Instant now = time.now();
        Instant from = parsed.orders().stream().map(ImportedOrder::placedAt).filter(d -> d != null).min(Instant::compareTo).orElse(now);
        Instant to = parsed.orders().stream().map(ImportedOrder::placedAt).filter(d -> d != null).max(Instant::compareTo).orElse(now);
        if (!to.isAfter(from)) to = from.plusSeconds(1);
        String name = fileName == null || fileName.isBlank() ? "orders.csv" : fileName;
        String label = "Margin Replay · " + name + " · " + parsed.orders().size() + " orders";
        SimulationJob job = new SimulationJob(Ids.prefixed("sim"), KIND, label, JobStatus.QUEUED, 0.0, from, to, ScenarioOverrides.none(), parsed.orders().size(), principal.actorRef(), now, null, null, null, null);
        jobs.insert(job);
        metrics.count("oms.simulation.jobs", "status", "submitted", "kind", KIND);
        CountDownLatch done = new CountDownLatch(1);
        inFlight.put(job.id(), done);
        final long size = bytes.length;
        Thread.ofVirtual().name("margin-replay-" + job.id()).start(() -> {
            TenantContext.set(tenant);
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(principal));
            try {
                run(job, parsed, name, size, opts);
            } finally {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
                done.countDown();
                inFlight.remove(job.id());
            }
        });
        return job;
    }

    /** Runs the bundled Shopify sample ({@value #SAMPLE_RESOURCE}) without an upload. */
    public SimulationJob submitSample(MarginReplayOptions options) {
        return submit(SAMPLE_NAME, sampleBytes(), options == null ? MarginReplayOptions.defaults() : options);
    }

    public static byte[] sampleBytes() {
        try (InputStream in = MarginReplayService.class.getClassLoader().getResourceAsStream(SAMPLE_RESOURCE)) {
            if (in == null) throw new NotFoundException("Bundled sample " + SAMPLE_RESOURCE + " is not on the classpath", Map.of("resource", SAMPLE_RESOURCE));
            return in.readAllBytes();
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + SAMPLE_RESOURCE, e); }
    }

    public SimulationJob get(String id) { return jobs.find(id).orElseThrow(() -> new NotFoundException("Simulation job " + id + " not found", Map.of("id", id))); }

    public Optional<SimulationJob> find(String id) { return jobs.find(id); }

    /** Blocks until the worker finished (tests, synchronous callers); false on timeout. */
    public boolean await(String id, Duration timeout) {
        CountDownLatch latch = inFlight.get(id);
        if (latch == null) return true;
        try { return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private void run(SimulationJob job, OrderCsvParser.Parsed parsed, String fileName, long bytes, MarginReplayOptions opts) {
        Instant started = time.now();
        jobs.markStarted(job.id(), started);
        MarginReplayReport report = new MarginReplayReport(opts);
        report.warnings.addAll(parsed.warnings());
        for (OrderCsvParser.Skipped s : parsed.skipped()) report.fileSkipped(s);
        report.ordersInFile += parsed.orders().size();
        report.parsed = parsed.orders().size();
        int total = parsed.orders().size(), processed = 0;
        JobStatus terminal = JobStatus.COMPLETED;
        try {
            for (ImportedOrder o : parsed.orders()) {
                if (jobs.cancelRequested(job.id())) { terminal = JobStatus.CANCELLED; break; }
                replayOne(o, opts, job.id(), started, report);
                processed++;
                if (processed % props.commitEvery() == 0 && processed < total) {
                    jobs.progress(job.id(), processed / (double) total, report.results(fileName, bytes, parsed.format(), parsed.rows(), true, processed, Duration.between(started, time.now()).toMillis(), RUNNING_ROW_CAP));
                }
            }
            Map<String, Object> results = report.results(fileName, bytes, parsed.format(), parsed.rows(), terminal != JobStatus.COMPLETED, processed, Duration.between(started, time.now()).toMillis(), 0);
            jobs.finish(job.id(), terminal, terminal == JobStatus.COMPLETED ? 1.0 : processed / (double) Math.max(1, total), results, null, time.now());
        } catch (RuntimeException e) {
            terminal = JobStatus.FAILED;
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Margin replay {} failed after {} orders", job.id(), processed, e);
            Map<String, Object> partial = report.results(fileName, bytes, parsed.format(), parsed.rows(), true, processed, Duration.between(started, time.now()).toMillis(), 0);
            jobs.finish(job.id(), terminal, processed / (double) Math.max(1, total), partial, error, time.now());
        }
        metrics.count("oms.simulation.jobs", "status", terminal.name().toLowerCase(), "kind", KIND);
        metrics.record("oms.simulation.job", Duration.between(started, time.now()), "kind", KIND);
        log.info("Margin replay {} {} ({} orders replayed, {} skipped, margin recovered {})", job.id(), terminal, report.planned, report.skipped, ReplayFindings.usd(report.intentMargin - report.baselineMargin));
    }

    private void replayOne(ImportedOrder o, MarginReplayOptions opts, String jobId, Instant fallbackNow, MarginReplayReport report) {
        Optional<UsGeocoder.Location> geo = UsGeocoder.locate(o.city(), o.state(), o.zip(), o.country());
        if (geo.isEmpty()) {
            String reason = UsGeocoder.isUs(o.country()) ? "destination not recognised (no US city, ZIP or state)" : "non-US destination (" + o.country().trim().toUpperCase() + ")";
            report.skip(o.orderId(), reason, o.sourceRow());
            return;
        }
        try {
            report.add(planner.replay(o, geo.get(), opts, jobId, fallbackNow));
        } catch (RuntimeException e) {
            log.warn("Replay of order {} failed: {}", o.orderId(), e.getMessage());
            report.skip(o.orderId(), "replay error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), o.sourceRow());
        }
    }

    private static Map<String, Integer> countSkips(OrderCsvParser.Parsed parsed) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (OrderCsvParser.Skipped s : parsed.skipped()) m.merge(s.reason(), 1, Integer::sum);
        return m;
    }
}
