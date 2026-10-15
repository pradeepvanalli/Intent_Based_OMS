package ai.intentoms.statemachine.service;

import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.config.StateMachineProperties;
import ai.intentoms.statemachine.model.MigrationReport;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import ai.intentoms.statemachine.model.ValidationIssue;
import ai.intentoms.statemachine.model.ValidationReport;
import ai.intentoms.statemachine.store.EntityStateRepository;
import ai.intentoms.statemachine.store.StateModelRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Versioned model registry (FR-SM-06, FR-SM-09, FR-SM-10). Published versions are immutable, so parsed models are cached
 * per (tenant, model, version) without expiry; the "latest version" lookup is cached for a few seconds because other
 * nodes may publish.
 */
@Service
public class StateModelRegistryImpl implements StateModelRegistry {
    private static final Logger log = LoggerFactory.getLogger(StateModelRegistryImpl.class);

    private final StateModelRepository models;
    private final EntityStateRepository entities;
    private final StateModelValidator validator;
    private final SlaTimerService slaTimers;
    private final EventStore events;
    private final AuditLog audit;
    private final AuthorizationService authz;
    private final TimeSource time;
    private final Metrics metrics;
    private final Cache<String, StateModel> byVersion;
    private final Cache<String, Optional<Integer>> latestVersion = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(10_000).build();

    public StateModelRegistryImpl(StateModelRepository models, EntityStateRepository entities, StateModelValidator validator, SlaTimerService slaTimers,
                                  EventStore events, AuditLog audit, AuthorizationService authz, TimeSource time, Metrics metrics, StateMachineProperties props) {
        this.models = models;
        this.entities = entities;
        this.validator = validator;
        this.slaTimers = slaTimers;
        this.events = events;
        this.audit = audit;
        this.authz = authz;
        this.time = time;
        this.metrics = metrics;
        this.byVersion = Caffeine.newBuilder().maximumSize(props.cacheSize()).build();
    }

    @Override
    public ValidationReport validate(String yaml) { return validator.validateYaml(yaml); }

    @Override
    @Transactional
    public StateModel publish(String yaml, String reason) {
        if (reason == null || reason.isBlank()) throw new ValidationException("A publish reason is required");
        StateModel model;
        try { model = StateModelParser.parse(yaml); }
        catch (IllegalArgumentException e) { throw new ValidationException("State model YAML could not be parsed: " + e.getMessage(), Map.of("issues", List.of(Map.of("severity", "error", "code", "parse_error", "message", e.getMessage(), "ref", "yaml")))); }
        ValidationReport report = validator.validate(model);
        if (!report.ok()) {
            List<Map<String, Object>> issues = new ArrayList<>();
            for (ValidationIssue i : report.issues()) issues.add(Map.of("severity", i.severity(), "code", i.code(), "message", i.message(), "ref", i.ref()));
            throw new ValidationException("State model " + model.id() + " v" + model.version() + " failed static checks (" + report.errors().size() + " error(s))", Map.of("issues", issues));
        }
        if (models.exists(model.id(), model.version())) throw new ConflictException("State model " + model.id() + " version " + model.version() + " is already published",
            Map.of("modelId", model.id(), "version", model.version()));
        String tenant = TenantContext.require();
        String by = authz.actorRef();
        Instant now = time.now();
        models.insert(model, by, reason, now);
        audit.record("statemodel.publish", "state_model", model.id() + "@" + model.version(), reason,
            Map.of("version", model.version(), "entity", model.entity(), "states", model.states().size(), "transitions", model.transitions().size(), "warnings", report.warnings().size()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelId", model.id());
        payload.put("version", model.version());
        payload.put("entity", model.entity());
        payload.put("reason", reason);
        payload.put("warnings", report.warnings().stream().map(ValidationIssue::message).toList());
        events.append("config", "statemodel:" + model.id(), -1, List.of(new NewEvent("statemodel.published", payload, Actor.parse(by))));
        byVersion.put(key(tenant, model.id(), model.version()), model);
        latestVersion.invalidate(tenant + "|" + model.id());
        metrics.count("oms.statemachine.models_published", "model", model.id());
        if (!report.warnings().isEmpty()) log.info("Published state model {} v{} with {} warning(s): {}", model.id(), model.version(), report.warnings().size(), report.warnings().stream().map(ValidationIssue::message).toList());
        return model;
    }

    @Override
    public StateModel get(String modelId, int version) {
        String tenant = TenantContext.require();
        StateModel m = byVersion.get(key(tenant, modelId, version), k -> models.find(modelId, version).orElse(null));
        if (m == null) throw new NotFoundException("State model " + modelId + " version " + version + " not found");
        return m;
    }

    @Override
    public StateModel latest(String modelId) {
        String tenant = TenantContext.require();
        Optional<Integer> v = latestVersion.get(tenant + "|" + modelId, k -> models.latestVersion(modelId));
        if (v == null || v.isEmpty()) throw new NotFoundException("State model " + modelId + " not found");
        return get(modelId, v.get());
    }

    @Override
    public boolean exists(String modelId, int version) {
        if (byVersion.getIfPresent(key(TenantContext.require(), modelId, version)) != null) return true;
        return models.exists(modelId, version);
    }

    @Override
    public List<StateModel> versions(String modelId) { return models.versions(modelId); }

    @Override
    public List<String> modelIds() { return models.modelIds(); }

    @Override
    @Transactional
    public MigrationReport migrate(String modelId, int fromVersion, int toVersion, boolean dryRun) {
        if (fromVersion == toVersion) throw new ValidationException("fromVersion and toVersion are the same (" + fromVersion + ")");
        get(modelId, fromVersion);
        StateModel target = get(modelId, toVersion);
        List<EntityStateRepository.Row> rows = entities.byModelVersion(modelId, fromVersion, !dryRun);
        List<MigrationReport.EntityMigration> per = new ArrayList<>(rows.size());
        int migrated = 0, skipped = 0;
        Instant now = time.now();
        Actor actor = Actor.parse(authz.actorRef());
        for (EntityStateRepository.Row row : rows) {
            Map<String, Integer> qty = QuantityBuckets.toMap(entities.buckets(row.entityType(), row.entityId()));
            TreeSet<String> unmapped = new TreeSet<>();
            for (String s : qty.keySet()) if (!target.hasState(s)) unmapped.add(s);
            if (!row.state().isEmpty() && qty.isEmpty() && !target.hasState(row.state())) unmapped.add(row.state());
            if (!unmapped.isEmpty()) {
                skipped++;
                per.add(new MigrationReport.EntityMigration(row.entityType(), row.entityId(), "skipped", "unmapped: " + String.join(", ", unmapped), qty));
                continue;
            }
            migrated++;
            if (!dryRun) {
                entities.updateVersion(row.entityType(), row.entityId(), toVersion, now);
                String dominant = QuantityBuckets.dominant(target, qty);
                if (!dominant.equals(row.state())) entities.updateState(row.entityType(), row.entityId(), dominant, now);
                slaTimers.reconcile(row.entityType(), row.entityId(), target, qty.keySet());
                var agg = Aggregates.of(row.entityType(), row.entityId());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("entityType", row.entityType());
                payload.put("entityId", row.entityId());
                payload.put("modelId", modelId);
                payload.put("fromVersion", fromVersion);
                payload.put("toVersion", toVersion);
                payload.put("states", qty);
                payload.put("state", dominant);
                events.append(agg.type(), agg.id(), -1, List.of(new NewEvent("statemodel.migrated", payload, actor, null, null, Map.of("entityType", row.entityType(), "entityId", row.entityId()))));
            }
            per.add(new MigrationReport.EntityMigration(row.entityType(), row.entityId(), "migrated", dryRun ? "dry run" : "ok", qty));
        }
        if (!dryRun) {
            audit.record("statemodel.migrate", "state_model", modelId, "migrate v" + fromVersion + " -> v" + toVersion,
                Map.of("fromVersion", fromVersion, "toVersion", toVersion, "total", rows.size(), "migrated", migrated, "skipped", skipped));
            metrics.count("oms.statemachine.entities_migrated", migrated, "model", modelId);
        }
        return new MigrationReport(modelId, fromVersion, toVersion, dryRun, rows.size(), migrated, skipped, per);
    }

    private static String key(String tenant, String modelId, int version) { return tenant + "|" + modelId + "|" + version; }
}
