package ai.intentoms.workflow.engine;

import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls {@code workflow_instance} for due rows and runs them through {@link StepRunner} (FR-WF-01). N virtual threads
 * ({@code oms.workflow.workers}, default 8) each loop: run one due instance; if none was due, sleep
 * {@code oms.workflow.poll-ms} (default 200). Several application instances can run workers concurrently because the
 * pick-up query uses {@code FOR UPDATE SKIP LOCKED}. With {@code oms.workflow.enabled=false} nothing polls and
 * callers (tests, ops tooling) drive {@link #runOnce()} instead.
 */
@Component
public class WorkflowWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);
    private final StepRunner runner;
    private final WorkflowRepository repo;
    private final WorkflowProperties props;
    private final TimeSource time;
    private final AtomicLong backlog = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public WorkflowWorker(StepRunner runner, WorkflowRepository repo, WorkflowProperties props, TimeSource time, Metrics metrics) {
        this.runner = runner;
        this.repo = repo;
        this.props = props;
        this.time = time;
        Gauge.builder("oms.workflow.due_backlog", backlog, AtomicLong::get).description("Workflow instances due for execution").register(metrics.registry());
    }

    @Override
    public void start() {
        if (running || !props.enabled()) {
            if (!props.enabled()) log.info("Workflow workers disabled (oms.workflow.enabled=false)");
            return;
        }
        running = true;
        for (int i = 0; i < props.workers(); i++) {
            Thread t = Thread.ofVirtual().name("workflow-worker-" + i).start(this::loop);
            threads.add(t);
        }
        log.info("Started {} workflow workers (poll {} ms)", props.workers(), props.pollMs());
    }

    private void loop() {
        while (running) {
            try {
                if (!runner.runNext()) Thread.sleep(props.pollMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.error("Workflow worker loop error: {}", t.toString());
                try { Thread.sleep(props.pollMs()); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /**
     * Synchronously run every due instance until none is due (an instance that schedules itself again immediately,
     * e.g. via {@code Next.goTo}, is processed repeatedly in the same call). Returns the number of step executions.
     */
    public int runOnce() {
        int n = 0;
        while (n < props.runOnceLimit() && runner.runNext()) n++;
        refreshBacklog();
        return n;
    }

    /** Current value of the due-backlog gauge. */
    public long backlog() { return backlog.get(); }

    @Scheduled(fixedDelayString = "${oms.workflow.backlog-refresh-ms:10000}")
    public void refreshBacklogScheduled() {
        if (props.enabled()) refreshBacklog();
    }

    private void refreshBacklog() {
        try { backlog.set(repo.countDue(time.now())); } catch (RuntimeException e) { log.debug("Backlog refresh failed: {}", e.toString()); }
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        threads.clear();
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
