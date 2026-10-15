package ai.intentoms.workflow.bulk;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link BulkOperationService} backed by {@code bulk_operation} (FR-WF-07). {@link #submit} records the operation and
 * appends {@code bulk.submitted}, then a virtual thread bound to the submitter's tenant and principal applies the
 * handler to each subject behind a token-bucket throttle, recording per-subject result or error in its own
 * transaction so progress is visible while running. {@code bulk.completed} is appended when the run ends. Dry runs
 * call the handler with {@code dryRun = true} and store the would-be results. {@link #rollback} undoes applied
 * subjects in reverse order, synchronously.
 */
@Service
public class DefaultBulkOperationService implements BulkOperationService {
    private static final Logger log = LoggerFactory.getLogger(DefaultBulkOperationService.class);
    static final String AGGREGATE = "workflow";
    private final ObjectProvider<BulkActionHandler> handlerBeans;
    private final BulkOperationRepository repo;
    private final EventStore events;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final Metrics metrics;
    private final TransactionTemplate tx;
    private final Map<String, CountDownLatch> inFlight = new ConcurrentHashMap<>();
    private volatile Map<String, BulkActionHandler> handlers;

    public DefaultBulkOperationService(ObjectProvider<BulkActionHandler> handlerBeans, BulkOperationRepository repo, EventStore events, TimeSource time,
                                       AuthorizationService authz, Metrics metrics, TransactionTemplate tx) {
        this.handlerBeans = handlerBeans;
        this.repo = repo;
        this.events = events;
        this.time = time;
        this.authz = authz;
        this.metrics = metrics;
        this.tx = tx;
    }

    private Map<String, BulkActionHandler> handlers() {
        Map<String, BulkActionHandler> h = handlers;
        if (h == null) {
            synchronized (this) {
                if (handlers == null) {
                    Map<String, BulkActionHandler> m = new TreeMap<>();
                    handlerBeans.orderedStream().forEach(b -> {
                        BulkActionHandler prev = m.put(b.action(), b);
                        if (prev != null) throw new IllegalStateException("Two BulkActionHandler beans for action '" + b.action() + "'");
                    });
                    handlers = Map.copyOf(m);
                }
                h = handlers;
            }
        }
        return h;
    }

    /** Register a handler at runtime (handlers discovered as beans are registered automatically). */
    public synchronized void register(BulkActionHandler handler) {
        Map<String, BulkActionHandler> m = new TreeMap<>(handlers());
        m.put(handler.action(), handler);
        handlers = Map.copyOf(m);
    }

    @Override
    public List<String> actions() { return new ArrayList<>(handlers().keySet()); }

    @Override
    public BulkOperation submit(String action, List<String> subjectIds, Map<String, Object> params, boolean dryRun, int perSecond) {
        BulkActionHandler handler = handlers().get(action);
        if (handler == null) throw new ValidationException("Unknown bulk action '" + action + "'", Map.of("known", actions()));
        if (subjectIds == null || subjectIds.isEmpty()) throw new ValidationException("subjectIds must not be empty");
        List<String> subjects = new ArrayList<>(new LinkedHashSet<>(subjectIds));
        String tenant = TenantContext.require();
        Principal principal = authz.currentPrincipal().orElseGet(() -> Principal.system(tenant));
        Instant now = time.now();
        BulkOperation b = new BulkOperation(Ids.prefixed("bulk"), action, params == null ? Map.of() : new LinkedHashMap<>(params), subjects, dryRun,
            Math.max(0, perSecond), BulkStatus.QUEUED, subjects.size(), 0, 0, List.of(), null, principal.actorRef(), now, null, null, null);
        tx.executeWithoutResult(s -> {
            repo.insert(tenant, b);
            events.append(AGGREGATE, b.id(), new NewEvent("bulk.submitted",
                Map.of("bulkId", b.id(), "action", action, "subjects", subjects.size(), "dryRun", dryRun, "perSecond", b.perSecond(), "params", b.params()), Actor.parse(principal.actorRef())));
        });
        metrics.count("oms.workflow.bulk", "action", action, "op", dryRun ? "dry_run" : "submitted");
        CountDownLatch done = new CountDownLatch(1);
        inFlight.put(b.id(), done);
        Thread.ofVirtual().name("bulk-" + b.id()).start(() -> {
            TenantContext.set(tenant);
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(principal));
            try {
                run(tenant, b, handler, principal);
            } finally {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
                done.countDown();
                inFlight.remove(b.id());
            }
        });
        return b;
    }

    private void run(String tenant, BulkOperation b, BulkActionHandler handler, Principal principal) {
        Instant started = time.now();
        tx.executeWithoutResult(s -> repo.markStarted(tenant, b.id(), started));
        Throttle throttle = new Throttle(b.perSecond());
        int ok = 0, failed = 0;
        for (String subject : b.subjectIds()) {
            throttle.acquire();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("subjectId", subject);
            boolean success = applyOne(handler, b, subject, result);
            if (success) ok++; else failed++;
            tx.executeWithoutResult(s -> repo.appendResult(tenant, b.id(), result, success));
        }
        Instant finished = time.now();
        String status = ok == 0 && failed > 0 ? BulkStatus.FAILED : BulkStatus.COMPLETED;
        int okCount = ok, failedCount = failed;
        tx.executeWithoutResult(s -> {
            repo.finish(tenant, b.id(), status, null, finished);
            events.append(AGGREGATE, b.id(), new NewEvent("bulk.completed",
                Map.of("bulkId", b.id(), "action", b.action(), "status", status, "total", b.total(), "ok", okCount, "failed", failedCount, "dryRun", b.dryRun(),
                    "durationMs", Duration.between(started, finished).toMillis()), Actor.parse(principal.actorRef())));
        });
        metrics.count("oms.workflow.bulk", "action", b.action(), "op", status.toLowerCase());
    }

    private static boolean applyOne(BulkActionHandler handler, BulkOperation b, String subject, Map<String, Object> result) {
        try {
            Map<String, Object> applied = handler.apply(subject, b.params(), b.dryRun());
            result.put("status", "ok");
            result.put("applied", applied == null ? Map.of() : applied);
            return true;
        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("error", abbreviate(e.toString()));
            log.warn("Bulk {} {} on {} failed: {}", b.id(), b.action(), subject, e.toString());
            return false;
        }
    }

    @Override
    public BulkOperation get(String id) {
        return repo.find(TenantContext.require(), id).orElseThrow(() -> new NotFoundException("Bulk operation " + id + " not found"));
    }

    @Override
    public Page<BulkOperation> list(int offset, int limit) {
        return repo.list(TenantContext.require(), Math.max(0, offset), limit <= 0 || limit > 500 ? 50 : limit);
    }

    @Override
    public BulkOperation rollback(String id) {
        String tenant = TenantContext.require();
        BulkOperation b = tx.execute(s -> {
            BulkOperation locked = repo.lock(tenant, id).orElseThrow(() -> new NotFoundException("Bulk operation " + id + " not found"));
            if (locked.dryRun()) throw new ConflictException("Bulk operation " + id + " was a dry run; nothing to roll back");
            if (!BulkStatus.COMPLETED.equals(locked.status()) && !BulkStatus.FAILED.equals(locked.status())) {
                throw new ConflictException("Bulk operation " + id + " is " + locked.status() + " and cannot be rolled back");
            }
            repo.setStatus(tenant, id, BulkStatus.ROLLING_BACK);
            return locked;
        });
        BulkActionHandler handler = handlers().get(b.action());
        if (handler == null) {
            tx.executeWithoutResult(s -> repo.setStatus(tenant, id, b.status()));
            throw new ConflictException("No handler registered for action '" + b.action() + "'");
        }
        List<Map<String, Object>> results = new ArrayList<>(b.results());
        int undone = 0, failed = 0;
        for (int i = results.size() - 1; i >= 0; i--) {
            Map<String, Object> r = results.get(i);
            if (!"ok".equals(r.get("status"))) continue;
            String subject = String.valueOf(r.get("subjectId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> applied = r.get("applied") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Map<String, Object> rb = new LinkedHashMap<>();
            try {
                Optional<Map<String, Object>> out = handler.rollback(subject, applied);
                rb.put("status", out.isPresent() ? "ok" : "noop");
                out.ifPresent(v -> rb.put("result", v));
                undone++;
            } catch (RuntimeException e) {
                rb.put("status", "error");
                rb.put("error", abbreviate(e.toString()));
                failed++;
                log.warn("Rollback of bulk {} {} on {} failed: {}", id, b.action(), subject, e.toString());
            }
            rb.put("at", time.now().toString());
            r.put("rollback", rb);
        }
        Instant now = time.now();
        String status = failed > 0 && undone == 0 ? BulkStatus.FAILED : BulkStatus.ROLLED_BACK;
        int undoneCount = undone, failedCount = failed;
        tx.executeWithoutResult(s -> {
            repo.rolledBack(tenant, id, results, status, now);
            events.append(AGGREGATE, id, new NewEvent("bulk.rolled_back",
                Map.of("bulkId", id, "action", b.action(), "undone", undoneCount, "failed", failedCount), Actor.parse(authz.actorRef())));
        });
        metrics.count("oms.workflow.bulk", "action", b.action(), "op", "rolled_back");
        return get(id);
    }

    /** Block until the asynchronous run of {@code id} has finished (or the timeout passes); returns the current state. */
    public BulkOperation await(String id, Duration timeout) {
        CountDownLatch latch = inFlight.get(id);
        if (latch != null) {
            try {
                if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) log.warn("Bulk operation {} still running after {}", id, timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return get(id);
    }

    private static String abbreviate(String s) { return s == null ? null : s.length() <= 1000 ? s : s.substring(0, 1000) + "…"; }

    /** Token bucket: {@code perSecond} permits refilled continuously; 0 means unthrottled. */
    static final class Throttle {
        private final int perSecond;
        private double tokens;
        private long lastNanos = System.nanoTime();

        Throttle(int perSecond) {
            this.perSecond = perSecond;
            this.tokens = perSecond;
        }

        void acquire() {
            if (perSecond <= 0) return;
            while (true) {
                long now = System.nanoTime();
                tokens = Math.min(perSecond, tokens + (now - lastNanos) / 1_000_000_000.0 * perSecond);
                lastNanos = now;
                if (tokens >= 1.0) { tokens -= 1.0; return; }
                long waitMs = (long) Math.ceil((1.0 - tokens) / perSecond * 1000);
                try { Thread.sleep(Math.max(1, waitMs)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
