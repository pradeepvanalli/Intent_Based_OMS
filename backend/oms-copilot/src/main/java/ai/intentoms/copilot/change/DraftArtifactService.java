package ai.intentoms.copilot.change;

import ai.intentoms.copilot.config.ConfigKeyCatalog;
import ai.intentoms.finance.compliance.ComplianceRule;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.monitoring.playbook.Playbook;
import ai.intentoms.monitoring.playbook.PlaybookService;
import ai.intentoms.monitoring.sla.SlaDefinition;
import ai.intentoms.monitoring.sla.SlaDefinitionService;
import ai.intentoms.order.model.OrderType;
import ai.intentoms.order.service.OrderTypeRegistry;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.StateModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staged draft artefacts and their human-only application (FR-CFG-02, GR-14). The copilot writes drafts here; a person
 * with the owning permission ({@code statemodel.publish} for state models, {@code config.admin} otherwise) applies one,
 * which performs the real write through the owning service, records an audit row and appends
 * {@code copilot.change_applied} on the {@code config} aggregate.
 */
@Service
public class DraftArtifactService {
    private final DraftArtifactRepository repo;
    private final StateModelRegistry stateModels;
    private final OrderTypeRegistry orderTypes;
    private final SlaDefinitionService slas;
    private final PlaybookService playbooks;
    private final ComplianceRuleLoader compliance;
    private final TenantConfigStore config;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final EventStore events;
    private final TimeSource time;

    public DraftArtifactService(DraftArtifactRepository repo, StateModelRegistry stateModels, OrderTypeRegistry orderTypes, SlaDefinitionService slas, PlaybookService playbooks,
                                ComplianceRuleLoader compliance, TenantConfigStore config, AuthorizationService authz, AuditLog audit, EventStore events, TimeSource time) {
        this.repo = repo;
        this.stateModels = stateModels;
        this.orderTypes = orderTypes;
        this.slas = slas;
        this.playbooks = playbooks;
        this.compliance = compliance;
        this.config = config;
        this.authz = authz;
        this.audit = audit;
        this.events = events;
        this.time = time;
    }

    public DraftArtifact stage(String changesetId, ProposedChange change) {
        String summary = change.rationale() == null ? change.kind() + " " + change.target() : change.rationale();
        return repo.insert(new DraftArtifact(Ids.prefixed("cpa"), changesetId, change.id(), change.kind(), change.target(), change.after(), summary, "draft", authz.actorRef(), time.now(),
            null, null, null, Map.of()));
    }

    public List<DraftArtifact> list(String status, String changesetId) { return repo.list(status, changesetId); }

    public DraftArtifact get(String id) { return repo.get(id); }

    /** The permission a person needs to apply an artefact of this kind. */
    public static String requiredPermission(ChangeKind kind) { return kind == ChangeKind.state_model ? Permissions.STATEMODEL_PUBLISH : Permissions.CONFIG_ADMIN; }

    /** Applies a staged artefact through its owning service. Human only; requires the owning permission; mandatory reason. */
    @Transactional
    public DraftArtifact apply(String artifactId, String reason) {
        if (reason == null || reason.isBlank()) throw new ValidationException("An apply reason is required (GR-10)");
        requireHuman();
        DraftArtifact a = repo.get(artifactId);
        if (!"draft".equals(a.status())) throw new ConflictException("Artefact " + artifactId + " is " + a.status() + ", not a draft");
        authz.require(requiredPermission(a.kind()));
        Map<String, Object> result = switch (a.kind()) {
            case state_model -> applyStateModel(a, reason);
            case order_type -> applyOrderType(a);
            case sla -> applySla(a);
            case playbook -> applyPlaybook(a);
            case compliance_rule -> applyCompliance(a);
            case tenant_config -> applyTenantConfig(a, reason);
            default -> throw new ValidationException("Artefact kind " + a.kind() + " is applied through the decision studio, not here");
        };
        String by = authz.actorRef();
        repo.markApplied(artifactId, by, reason, result, time.now());
        audit.record("copilot.change_applied", "copilot_artifact", artifactId, reason, Map.of("kind", a.kind().name(), "target", a.target(), "changesetId", a.changesetId(), "result", result));
        Map<String, Object> payload = new LinkedHashMap<>(result);
        payload.put("artifactId", artifactId);
        payload.put("changesetId", a.changesetId());
        payload.put("changeId", a.changeId());
        payload.put("kind", a.kind().name());
        payload.put("target", a.target());
        payload.put("reason", reason);
        events.append("config", "copilot:" + a.changesetId(), new NewEvent("copilot.change_applied", payload, Actor.parse(by)));
        return repo.get(artifactId);
    }

    /** Publishes a staged state model draft (FR-SM-12). Same as {@link #apply} but refuses other kinds. */
    public DraftArtifact publishStateModelDraft(String artifactId, String reason) {
        DraftArtifact a = repo.get(artifactId);
        if (a.kind() != ChangeKind.state_model) throw new ValidationException("Artefact " + artifactId + " is a " + a.kind() + ", not a state model");
        return apply(artifactId, reason);
    }

    /** Rejects (discards) a staged artefact; status becomes {@code rejected}. */
    @Transactional
    public DraftArtifact reject(String artifactId, String reason) {
        requireHuman();
        DraftArtifact a = repo.get(artifactId);
        if (!"draft".equals(a.status())) throw new ConflictException("Artefact " + artifactId + " is " + a.status() + ", not a draft");
        repo.markRejected(artifactId, authz.actorRef(), reason, time.now());
        audit.record("copilot.artifact_rejected", "copilot_artifact", artifactId, reason, Map.of("kind", a.kind().name(), "target", a.target()));
        return repo.get(artifactId);
    }

    // ------------------------------------------------------------------ per-kind writes

    private Map<String, Object> applyStateModel(DraftArtifact a, String reason) {
        StateModel m = stateModels.publish(a.content(), reason);
        return Map.of("modelId", m.id(), "version", m.version(), "entity", m.entity());
    }

    private Map<String, Object> applyOrderType(DraftArtifact a) {
        Map<String, Object> m = Json.yamlMap(a.content());
        if (m.get("orderTypes") instanceof List<?> l && l.size() == 1 && l.get(0) instanceof Map<?, ?> single) m = Json.toMap(single);
        OrderType t = orderTypes.upsert(Json.convert(m, OrderType.class));
        return Map.of("orderType", t.name());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applySla(DraftArtifact a) {
        Map<String, Object> m = Json.yamlMap(a.content());
        List<Object> entries = m.get("slas") instanceof List<?> l ? (List<Object>) l : List.of(m);
        List<String> ids = new ArrayList<>();
        for (Object o : entries) {
            Map<?, ?> em = (Map<?, ?>) o;
            SlaDefinition saved = slas.upsert(new SlaDefinition(null, str(em.get("orderType")), str(em.get("entity")), str(em.get("state")), str(em.get("maxAge")), str(em.get("onBreach")), str(em.get("expectedNextEventWithin")), null));
            ids.add(saved.id());
        }
        if (m.get("expectedNextEventWithin") instanceof Map<?, ?> w) {
            for (var e : w.entrySet()) {
                SlaDefinition existing = slas.find("*", "order_line", String.valueOf(e.getKey())).orElse(null);
                slas.upsert(new SlaDefinition(null, "*", "order_line", String.valueOf(e.getKey()), existing == null ? null : existing.maxAge(), existing == null ? null : existing.onBreach(), String.valueOf(e.getValue()), null));
            }
        }
        return Map.of("slaIds", ids);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyPlaybook(DraftArtifact a) {
        Map<String, Object> m = Json.yamlMap(a.content());
        List<Object> entries = m.get("playbooks") instanceof List<?> l ? (List<Object>) l : List.of(m);
        List<String> names = new ArrayList<>();
        Actor actor = Actor.parse(authz.actorRef());
        for (Object o : entries) {
            Map<String, Object> pm = (Map<String, Object>) o;
            List<Map<String, Object>> steps = pm.get("steps") instanceof List<?> sl ? (List<Map<String, Object>>) (List<?>) sl : List.of();
            Playbook saved = playbooks.save(new Playbook(String.valueOf(pm.get("name")), pm.get("description") == null ? null : String.valueOf(pm.get("description")), steps), actor);
            names.add(saved.name());
        }
        if (m.get("spendLimits") instanceof Map<?, ?> sl) config.set("playbooks.spend_limits", sl);
        return Map.of("playbooks", names);
    }

    private Map<String, Object> applyCompliance(DraftArtifact a) {
        List<ComplianceRule> rules = compliance.loadYaml(a.content(), "copilot:" + a.id());
        return Map.of("ruleIds", rules.stream().map(ComplianceRule::id).toList());
    }

    private Map<String, Object> applyTenantConfig(DraftArtifact a, String reason) {
        Map<String, Object> m = Json.yamlMap(a.content());
        List<String> problems = new ArrayList<>();
        m.forEach((k, v) -> problems.addAll(ConfigKeyCatalog.validate(k, v)));
        if (!problems.isEmpty()) throw new ValidationException("Tenant configuration artefact is no longer valid", Map.of("problems", problems));
        List<String> keys = new ArrayList<>();
        m.forEach((k, v) -> {
            Object previous = config.get(k).orElse(null);
            config.set(k, v instanceof String s ? Json.write(s) : v);
            audit.record("config.set", "tenant_config", k, reason, Map.of("previous", previous == null ? "" : previous, "value", v, "via", "copilot"));
            keys.add(k);
        });
        return Map.of("keys", keys);
    }

    private void requireHuman() {
        Principal p = authz.requirePrincipal();
        if (p.type() == Principal.Type.DECISION || p.type() == Principal.Type.AGENT) throw new ForbiddenException("Only people may apply copilot artefacts (GR-14)");
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
