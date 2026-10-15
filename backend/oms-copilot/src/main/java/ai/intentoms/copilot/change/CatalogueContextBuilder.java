package ai.intentoms.copilot.change;

import ai.intentoms.copilot.config.ConfigKeyCatalog;
import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.finance.compliance.ComplianceRule;
import ai.intentoms.finance.compliance.ComplianceRuleRepository;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.monitoring.exception.ExceptionTypeDef;
import ai.intentoms.monitoring.playbook.Playbook;
import ai.intentoms.monitoring.playbook.PlaybookService;
import ai.intentoms.monitoring.sla.SlaDefinition;
import ai.intentoms.monitoring.sla.SlaDefinitionService;
import ai.intentoms.order.model.OrderType;
import ai.intentoms.order.service.OrderTypeRegistry;
import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.workflow.model.WorkflowDefinition;
import ai.intentoms.workflow.task.TaskQueue;
import ai.intentoms.workflow.task.TaskQueueService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Builds the "catalogue context" the configuration copilot reasons over (FR-CFG-01): every live artefact of the tenant
 * (names, purposes, current YAML), the vocabularies it may reference (triggers, actions, queues, workflow types, nodes,
 * CEL roots), the whitelisted config keys with their current values, and the exact artefact formats from the prompt
 * templates. The same lookups give {@link #current} the "before" text for diffs.
 */
@Component
public class CatalogueContextBuilder {
    private final DecisionDefinitionRepository decisions;
    private final StateModelRegistry stateModels;
    private final OrderTypeRegistry orderTypes;
    private final SlaDefinitionService slas;
    private final PlaybookService playbooks;
    private final ExceptionService exceptions;
    private final ComplianceRuleRepository compliance;
    private final TenantConfigStore config;
    private final TaskQueueService queues;
    private final NodeRegistry nodes;
    private final ObjectProvider<WorkflowDefinition> workflows;
    private final PromptTemplates templates;

    public CatalogueContextBuilder(DecisionDefinitionRepository decisions, StateModelRegistry stateModels, OrderTypeRegistry orderTypes, SlaDefinitionService slas,
                                   PlaybookService playbooks, ExceptionService exceptions, ComplianceRuleRepository compliance, TenantConfigStore config,
                                   TaskQueueService queues, NodeRegistry nodes, ObjectProvider<WorkflowDefinition> workflows, PromptTemplates templates) {
        this.decisions = decisions;
        this.stateModels = stateModels;
        this.orderTypes = orderTypes;
        this.slas = slas;
        this.playbooks = playbooks;
        this.exceptions = exceptions;
        this.compliance = compliance;
        this.config = config;
        this.queues = queues;
        this.nodes = nodes;
        this.workflows = workflows;
        this.templates = templates;
    }

    /** The full catalogue as the model sees it. {@code scope} is echoed so the model can focus; nothing is omitted. */
    public Map<String, Object> build(String scope) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("scopeHint", scope == null ? "" : scope);
        ctx.put("decisions", decisionSummaries());
        ctx.put("stateModels", stateModelSummaries());
        ctx.put("orderTypes", orderTypes.all().stream().map(this::orderTypeMap).toList());
        ctx.put("slas", slas.list().stream().map(this::slaMap).toList());
        ctx.put("playbooks", playbooks.list().stream().map(Playbook::asMap).toList());
        ctx.put("playbookActions", playbookActions());
        ctx.put("spendLimits", playbooks.spendLimits());
        ctx.put("exceptionTypes", exceptions.taxonomy().stream().map(this::exceptionTypeMap).toList());
        ctx.put("queues", queues.list().stream().map(TaskQueue::name).toList());
        ctx.put("complianceRules", compliance.all().stream().map(this::complianceMap).toList());
        ctx.put("tenantConfig", tenantConfigEntries());
        ctx.put("workflowTypes", workflowTypes());
        ctx.put("nodes", nodes.active().stream().map(this::nodeMap).toList());
        ctx.put("celRoots", new TreeSet<>(CelEngine.STANDARD_ROOTS));
        ctx.put("fulfilmentPolicies", List.of("ship_complete", "ship_as_available", "earliest_partial", "pickup_consolidated"));
        return ctx;
    }

    /** Current text of an artefact, or empty when it does not exist yet. */
    public Optional<String> current(ChangeKind kind, String target) {
        try {
            switch (kind) {
                case decision_rule, decision_criteria, decision_bands, decision_new -> {
                    List<DecisionDefinitionRepository.Row> versions = decisions.versions(target);
                    if (versions.isEmpty()) return Optional.empty();
                    return Optional.of(versions.stream().filter(r -> "live".equals(r.definition().status())).findFirst().orElse(versions.get(0)).definition().yaml());
                }
                case state_model -> { return stateModels.modelIds().contains(target) ? Optional.of(stateModels.latest(target).yaml()) : Optional.empty(); }
                case order_type -> { return orderTypes.all().stream().filter(t -> t.name().equals(target)).findFirst().map(t -> Json.toYaml(orderTypeMap(t))); }
                case sla -> { return Optional.of(Json.toYaml(Map.of("slas", slas.list().stream().map(this::slaMap).toList()))); }
                case playbook -> {
                    List<Map<String, Object>> all = playbooks.list().stream().filter(p -> target == null || target.isBlank() || "playbooks".equals(target) || p.name().equals(target)).map(Playbook::asMap).toList();
                    return all.isEmpty() ? Optional.empty() : Optional.of(Json.toYaml(Map.of("playbooks", all)));
                }
                case compliance_rule -> { return Optional.of(Json.toYaml(Map.of("rules", compliance.all().stream().map(this::complianceMap).toList()))); }
                case tenant_config -> {
                    Map<String, Object> cur = new LinkedHashMap<>();
                    for (String k : ConfigKeyCatalog.entries().keySet()) config.get(k).ifPresent(v -> cur.put(k, v));
                    return Optional.of(Json.toYaml(cur));
                }
            }
        } catch (ai.intentoms.platform.error.NotFoundException e) { return Optional.empty(); }
        return Optional.empty();
    }

    /** Next version number a state model proposal must carry (latest + 1, or 1 for a new model). */
    public int nextStateModelVersion(String modelId) {
        return stateModels.modelIds().contains(modelId) ? stateModels.latest(modelId).version() + 1 : 1;
    }

    public List<String> workflowTypes() {
        List<String> out = new ArrayList<>();
        workflows.orderedStream().map(WorkflowDefinition::type).distinct().sorted().forEach(out::add);
        return out;
    }

    public List<String> playbookActions() {
        TreeSet<String> actions = new TreeSet<>(ArtifactValidator.KNOWN_PLAYBOOK_ACTIONS);
        actions.addAll(playbooks.registeredActions());
        return new ArrayList<>(actions);
    }

    public String formats() { return templates.formats(); }

    // ------------------------------------------------------------------ summaries

    private List<Map<String, Object>> decisionSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : decisions.names()) {
            List<DecisionDefinitionRepository.Row> versions = decisions.versions(name);
            if (versions.isEmpty()) continue;
            DecisionDefinitionRepository.Row shown = versions.stream().filter(r -> "live".equals(r.definition().status())).findFirst().orElse(versions.get(0));
            DecisionDefinition d = shown.definition();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("tier", d.tier().name());
            m.put("owner", d.owner());
            m.put("status", d.status());
            m.put("shownVersion", d.version());
            m.put("latestVersion", versions.get(0).definition().version());
            m.put("purpose", d.main().map(QuestionDef::effectiveInstructions).orElse(""));
            m.put("default", d.dflt());
            m.put("inputShape", DecisionInputShapes.describe(name));
            m.put("goldenSet", d.evaluation() == null ? null : d.evaluation().goldenSet());
            m.put("yaml", d.yaml());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> stateModelSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : stateModels.modelIds()) {
            StateModel m = stateModels.latest(id);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("entity", m.entity());
            s.put("latestVersion", m.version());
            s.put("nextVersion", m.version() + 1);
            s.put("states", m.states().stream().map(st -> st.id()).toList());
            s.put("triggers", m.transitions().stream().map(t -> t.trigger()).distinct().toList());
            s.put("yaml", m.yaml());
            out.add(s);
        }
        return out;
    }

    Map<String, Object> orderTypeMap(OrderType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        if (t.description() != null) m.put("description", t.description());
        m.put("headerStateModel", t.headerStateModel());
        m.put("lineStateModel", t.lineStateModel());
        m.put("workflowType", t.workflowType());
        m.put("decisions", t.decisions());
        m.put("channelDefaults", t.channelDefaults());
        if (t.returnWindowDays() != null) m.put("returnWindowDays", t.returnWindowDays());
        if (!t.sourcing().isEmpty()) m.put("sourcing", t.sourcing());
        return m;
    }

    Map<String, Object> slaMap(SlaDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderType", d.orderType());
        m.put("entity", d.entity());
        m.put("state", d.state());
        if (d.maxAge() != null) m.put("maxAge", d.maxAge());
        if (d.onBreach() != null) m.put("onBreach", d.onBreach());
        if (d.expectedNextEventWithin() != null) m.put("expectedNextEventWithin", d.expectedNextEventWithin());
        return m;
    }

    Map<String, Object> complianceMap(ComplianceRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("scope", r.scope());
        m.put("when", r.when());
        m.put("action", r.action());
        m.put("reason_code", r.reasonCode());
        m.put("severity", r.severity());
        m.put("message", r.message());
        m.put("priority", r.priority());
        m.put("active", r.active());
        return m;
    }

    private Map<String, Object> exceptionTypeMap(ExceptionTypeDef t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", t.type());
        m.put("severity", t.severity());
        m.put("queue", t.queue());
        m.put("playbook", t.playbook());
        m.put("slaMinutes", t.slaMinutes());
        return m;
    }

    private Map<String, Object> nodeMap(Node n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("name", n.name());
        m.put("type", n.type());
        m.put("state", n.state());
        m.put("capabilities", n.capabilities());
        return m;
    }

    private List<Map<String, Object>> tenantConfigEntries() {
        List<Map<String, Object>> out = new ArrayList<>();
        ConfigKeyCatalog.entries().forEach((k, e) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("type", e.type().name().toLowerCase());
            m.put("description", e.description());
            if (e.min() != null) m.put("min", e.min());
            if (e.max() != null) m.put("max", e.max());
            if (!e.allowedValues().isEmpty()) m.put("allowedValues", new TreeSet<>(e.allowedValues()));
            m.put("current", config.get(k).orElse(null));
            out.add(m);
        });
        return out;
    }
}
