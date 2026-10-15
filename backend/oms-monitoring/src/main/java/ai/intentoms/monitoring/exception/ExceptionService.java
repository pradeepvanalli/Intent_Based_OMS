package ai.intentoms.monitoring.exception;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.monitoring.alert.AlertDedupPolicy;
import ai.intentoms.monitoring.playbook.PlaybookService;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exception lifecycle (FR-MON-03/04). {@link #raise(ExceptionRequest)} classifies the signal with the Jev Choice
 * {@code exception_classify}, looks up severity/queue/playbook/SLA in the taxonomy, de-duplicates against an active
 * exception with the same (order, type, code) inside {@code alerts.dedupe_minutes}, stores the row, appends
 * {@code exception.raised}, creates the human task when the classification requires a person (GR-03 escape /
 * escalation), then runs the playbook. {@link #raise(ExceptionRequest, String)} skips classification for signals whose
 * type is already certain (SLA breach, stuck, workflow failure).
 */
@Service
public class ExceptionService {
    private static final Logger log = LoggerFactory.getLogger(ExceptionService.class);
    private static final Actor SYSTEM = Actor.system("exception-service");

    private final ExceptionRepository repo;
    private final ExceptionClassifier classifier;
    private final PlaybookService playbooks;
    private final HumanTaskService tasks;
    private final ExceptionTaskFactory taskFactory;
    private final OrderRepository orders;
    private final EventStore events;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final Metrics metrics;
    private final TransactionTemplate tx;
    private final AuthorizationService authz;

    public ExceptionService(ExceptionRepository repo, ExceptionClassifier classifier, PlaybookService playbooks, HumanTaskService tasks, ExceptionTaskFactory taskFactory,
                            OrderRepository orders, EventStore events, TenantConfigStore config, TimeSource time, Metrics metrics, TransactionTemplate tx, AuthorizationService authz) {
        this.repo = repo;
        this.classifier = classifier;
        this.playbooks = playbooks;
        this.tasks = tasks;
        this.taskFactory = taskFactory;
        this.orders = orders;
        this.events = events;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
        this.tx = tx;
        this.authz = authz;
    }

    /** Classify, store, route and remediate a raw signal. */
    public ExceptionRecord raise(ExceptionRequest req) { return raise(req, null, null); }

    /** Store, route and remediate a signal whose taxonomy type is known ({@code null} type classifies). */
    public ExceptionRecord raise(ExceptionRequest req, String type) { return raise(req, type, null); }

    /** Store with the source ledger event id so a re-delivered event finds its exception instead of raising again. */
    public ExceptionRecord raise(ExceptionRequest req, String type, String sourceEventId) { return raise(req, type, sourceEventId, null); }

    /**
     * @param type             taxonomy type, or null to classify
     * @param sourceEventId    ledger event that caused this exception (subscribers pass it so re-deliveries are no-ops)
     * @param playbookOverride playbook to run instead of the taxonomy default (SLA {@code onBreach: playbook.<name>}); null keeps the default
     */
    public ExceptionRecord raise(ExceptionRequest req, String type, String sourceEventId, String playbookOverride) {
        if (sourceEventId != null) {
            Optional<ExceptionRecord> already = repo.findBySourceEvent(sourceEventId);
            if (already.isPresent()) return already.get();
        }
        Actor actor = currentActor();
        Optional<Order> order = req.orderId() == null ? Optional.empty() : orders.find(req.orderId());
        ExceptionClassifier.Classification c = type == null ? classifier.classify(req, order, actor) : null;
        ExceptionTypeDef def = c != null ? c.definition() : classifier.definition(type);
        DecisionResult result = c == null ? null : c.result();
        Instant now = time.now();
        Duration window = dedupeWindow();

        Outcome outcome = tx.execute(s -> {
            Optional<ExceptionRecord> dup = repo.findActiveDuplicate(req.orderId(), def.type(), req.code(), now.minus(window));
            if (dup.isPresent()) {
                ExceptionRecord r = repo.update(dup.get().recurred(now));
                events.append(r.aggregateType(), r.aggregateId(), new NewEvent("exception.recurred", Map.of("exceptionId", r.id(), "type", r.type(), "code", r.code(), "occurrences", r.occurrences()), actor));
                metrics.count("oms.monitoring.exceptions_deduplicated", "type", r.type());
                return new Outcome(r, true);
            }
            ExceptionRecord created = repo.insert(new ExceptionRecord(Ids.prefixed("exc"), req.orderId(), req.lineId(), req.nodeId(), def.type(), def.severity(), def.queue(), ExceptionStatus.OPEN,
                req.source(), req.code(), req.message(), req.details(), result == null ? null : result.recordId(), result == null ? 1.0 : result.confidence(),
                playbookOverride == null ? def.playbook() : playbookOverride, List.of(), null,
                null, now.plus(Duration.ofMinutes(def.slaMinutes())), null, null, null, null, null, 1, now, sourceEventId, now, now));
            events.append(created.aggregateType(), created.aggregateId(), new NewEvent("exception.raised", raisedPayload(created, result), actor));
            metrics.count("oms.monitoring.exceptions_raised", "type", created.type(), "severity", created.severity(), "source", created.source());
            return new Outcome(created, false);
        });
        if (outcome == null) throw new IllegalStateException("exception insert returned no outcome");
        if (outcome.duplicate()) return outcome.record();

        ExceptionRecord exc = outcome.record();
        if (result != null && result.requiresHuman()) exc = ensureTask(exc, def.queue(), actor);
        try {
            playbooks.run(exc, result);
        } catch (RuntimeException e) {
            log.error("Playbook run failed for exception {}: {}", exc.id(), e.toString());
            metrics.count("oms.monitoring.playbook_errors");
        }
        return repo.get(exc.id());
    }

    private record Outcome(ExceptionRecord record, boolean duplicate) {}

    /** Create the queue task for an exception if none is linked (route to a person, FR-MON-03). */
    public ExceptionRecord ensureTask(ExceptionRecord exc, String queue, Actor actor) {
        ExceptionRecord current = repo.get(exc.id());
        if (current.taskId() != null) return current;
        HumanTask task = tasks.create(taskFactory.request(current, queue == null ? current.queue() : queue));
        ExceptionRecord linked = repo.update(current.withTask(task.id()));
        events.append(linked.aggregateType(), linked.aggregateId(), new NewEvent("exception.task_created", Map.of("exceptionId", linked.id(), "taskId", task.id(), "queue", task.queue()), actor));
        return linked;
    }

    /** Store the record id of a decision run on behalf of the exception (SLA {@code onBreach: decision.<name>}). */
    public ExceptionRecord attachDecision(String exceptionId, DecisionResult r, Map<String, Object> extraDetails) {
        ExceptionRecord e = repo.get(exceptionId);
        Map<String, Object> details = new LinkedHashMap<>(e.details());
        details.put("decision", r.decision());
        details.put("decisionValue", r.value() == null ? "" : r.value());
        details.put("decisionAction", r.action());
        details.put("decisionConfidence", r.confidence());
        if (extraDetails != null) details.putAll(extraDetails);
        ExceptionRecord updated = repo.update(e.withDecisionRecord(r.recordId(), details));
        events.append(updated.aggregateType(), updated.aggregateId(), new NewEvent("exception.decision_attached",
            Map.of("exceptionId", updated.id(), "decision", r.decision(), "recordId", r.recordId() == null ? "" : r.recordId(), "value", r.value() == null ? "" : r.value(), "action", r.action()), SYSTEM));
        return updated;
    }

    public ExceptionRecord assign(String id, String assignee) {
        if (assignee == null || assignee.isBlank()) throw new ValidationException("assignee is required");
        ExceptionRecord e = repo.get(id);
        if (!e.isActive()) throw new ConflictException("Exception " + id + " is " + e.status(), Map.of("status", e.status()));
        Actor actor = currentActor();
        ExceptionRecord updated = repo.update(e.withAssignee(assignee, ExceptionStatus.IN_PROGRESS));
        events.append(updated.aggregateType(), updated.aggregateId(), new NewEvent("exception.assigned", Map.of("exceptionId", id, "assignee", assignee), actor));
        if (updated.taskId() != null) {
            try {
                HumanTask t = tasks.get(updated.taskId());
                if (t.isOpen() && (t.assignee() == null || !t.assignee().equals(assignee))) tasks.claim(updated.taskId(), assignee);
            } catch (RuntimeException ex) { log.debug("Could not claim task {} for {}: {}", updated.taskId(), assignee, ex.toString()); }
        }
        return updated;
    }

    public ExceptionRecord resolve(String id, String resolution, String note) {
        if (resolution == null || resolution.isBlank()) throw new ValidationException("resolution is required");
        ExceptionRecord e = repo.get(id);
        if (!e.isActive()) throw new ConflictException("Exception " + id + " is already " + e.status(), Map.of("status", e.status()));
        Actor actor = currentActor();
        Instant now = time.now();
        ExceptionRecord updated = repo.update(e.resolved(ExceptionStatus.RESOLVED, resolution, note, now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exceptionId", id);
        payload.put("type", updated.type());
        payload.put("resolution", resolution);
        if (note != null) payload.put("note", note);
        payload.put("minutesOpen", Duration.between(updated.createdAt(), now).toMinutes());
        payload.put("withinSla", updated.slaDueAt() == null || !now.isAfter(updated.slaDueAt()));
        events.append(updated.aggregateType(), updated.aggregateId(), new NewEvent("exception.resolved", payload, actor));
        metrics.count("oms.monitoring.exceptions_resolved", "type", updated.type(), "resolution", resolution);
        completeTask(updated, "resolved", resolution, note);
        return updated;
    }

    public ExceptionRecord cancel(String id, String reason) {
        ExceptionRecord e = repo.get(id);
        if (!e.isActive()) throw new ConflictException("Exception " + id + " is already " + e.status(), Map.of("status", e.status()));
        ExceptionRecord updated = repo.update(e.resolved(ExceptionStatus.CANCELLED, "cancelled", reason, time.now()));
        events.append(updated.aggregateType(), updated.aggregateId(), new NewEvent("exception.cancelled", Map.of("exceptionId", id, "reason", reason == null ? "" : reason), currentActor()));
        completeTask(updated, "cancelled", reason == null ? "cancelled" : reason, null);
        return updated;
    }

    private void completeTask(ExceptionRecord e, String outcome, String reason, String note) {
        if (e.taskId() == null) return;
        try {
            HumanTask t = tasks.get(e.taskId());
            if (t.isOpen()) tasks.complete(e.taskId(), outcome, reason, note == null ? Map.of("exceptionId", e.id()) : Map.of("exceptionId", e.id(), "note", note));
        } catch (RuntimeException ex) {
            log.warn("Could not complete task {} for exception {}: {}", e.taskId(), e.id(), ex.toString());
        }
    }

    public Page<ExceptionRecord> list(ExceptionQuery q) { return repo.list(q); }

    public ExceptionRecord get(String id) { return repo.get(id); }

    /** Exception raised for a ledger event (idempotent subscribers). */
    public Optional<ExceptionRecord> findBySourceEvent(String eventId) { return repo.findBySourceEvent(eventId); }

    public List<ExceptionTypeDef> taxonomy() { return classifier.taxonomy(); }

    Duration dedupeWindow() { return Duration.ofMinutes(config.getInt(AlertDedupPolicy.WINDOW_KEY, AlertDedupPolicy.DEFAULT_WINDOW_MINUTES)); }

    private Actor currentActor() {
        try { return Actor.parse(authz.actorRef()); } catch (RuntimeException e) { return SYSTEM; }
    }

    private static Map<String, Object> raisedPayload(ExceptionRecord e, DecisionResult r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("exceptionId", e.id());
        p.put("type", e.type());
        p.put("severity", e.severity());
        p.put("queue", e.queue());
        p.put("source", e.source());
        p.put("code", e.code());
        p.put("message", e.message());
        if (e.orderId() != null) p.put("orderId", e.orderId());
        if (e.lineId() != null) p.put("lineId", e.lineId());
        if (e.nodeId() != null) p.put("nodeId", e.nodeId());
        if (e.playbook() != null) p.put("playbook", e.playbook());
        if (e.slaDueAt() != null) p.put("slaDueAt", e.slaDueAt().toString());
        if (r != null) {
            p.put("classificationRecordId", r.recordId() == null ? "" : r.recordId());
            p.put("classificationConfidence", r.confidence());
            p.put("classificationLayer", r.layer().name());
            p.put("requiresHuman", r.requiresHuman());
        }
        return p;
    }

}
