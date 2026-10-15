package ai.intentoms.copilot.change;

import ai.intentoms.copilot.config.ConfigKeyCatalog;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.LintReport;
import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.monitoring.playbook.PlaybookService;
import ai.intentoms.monitoring.sla.SlaDefinition;
import ai.intentoms.order.model.OrderType;
import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.ValidationIssue;
import ai.intentoms.statemachine.model.ValidationReport;
import ai.intentoms.workflow.task.TaskQueue;
import ai.intentoms.workflow.task.TaskQueueService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic validation of every proposed artefact before it can be staged (GR-04: model text never becomes
 * configuration without a typed parse). Decisions go through the studio parser and linter, CEL through
 * {@link CelEngine#validate}, state models through the registry's static checks, order types through the record
 * constructor and registry lookups, SLAs and playbooks through their schemas, compliance rules through the finance
 * loader's parser and tenant config through {@link ConfigKeyCatalog}.
 */
@Component
public class ArtifactValidator {
    public static final Set<String> KNOWN_PLAYBOOK_ACTIONS = Set.of("notify", "create_task", "hold", "classify", "log", "resource", "repromise", "contact_customer", "reship", "refund",
        "cancel", "reauthorize", "retry_workflow");
    private static final Set<String> GATE_KEYS = Set.of("action", "minConfidence", "when", "requiresDecision", "onFailure", "spendLimit", "amountUsd", "queue", "holdType", "blocks",
        "channel", "template", "note", "reason", "nodeId", "ttl", "priority", "title");
    private static final Set<String> POLICIES = Set.of("ship_complete", "ship_as_available", "earliest_partial", "pickup_consolidated");

    private final DecisionStudioService studio;
    private final DecisionDefinitionParser parser;
    private final DecisionDefinitionRepository decisions;
    private final CelEngine cel;
    private final StateModelRegistry stateModels;
    private final CatalogueContextBuilder catalogue;
    private final PlaybookService playbooks;
    private final ComplianceRuleLoader compliance;
    private final TaskQueueService queues;

    public ArtifactValidator(DecisionStudioService studio, DecisionDefinitionParser parser, DecisionDefinitionRepository decisions, CelEngine cel, StateModelRegistry stateModels,
                             CatalogueContextBuilder catalogue, PlaybookService playbooks, ComplianceRuleLoader compliance, TaskQueueService queues) {
        this.studio = studio;
        this.parser = parser;
        this.decisions = decisions;
        this.cel = cel;
        this.stateModels = stateModels;
        this.catalogue = catalogue;
        this.playbooks = playbooks;
        this.compliance = compliance;
        this.queues = queues;
    }

    /**
     * Validates one artefact. {@code proposedTargets} are the (kind, target) pairs of the same change set so a new order
     * type may reference a state model or decision proposed alongside it.
     */
    public ValidationResult validate(ChangeKind kind, String target, String after, Set<String> proposedTargets) {
        if (after == null || after.isBlank()) return ValidationResult.error("the proposed artefact is empty");
        try {
            return switch (kind) {
                case decision_rule, decision_criteria, decision_bands, decision_new -> decision(kind, target, after);
                case state_model -> stateModel(target, after);
                case order_type -> orderType(target, after, proposedTargets);
                case sla -> sla(after, proposedTargets);
                case playbook -> playbook(after);
                case compliance_rule -> complianceRules(after);
                case tenant_config -> tenantConfig(after);
            };
        } catch (ValidationException e) {
            ValidationResult.Collector c = new ValidationResult.Collector().error(e.getMessage());
            Object issues = e.details().get("issues");
            if (issues == null) issues = e.details().get("problems");
            if (issues instanceof List<?> l) l.forEach(i -> c.error(String.valueOf(i instanceof Map<?, ?> m && m.get("message") != null ? m.get("message") : i)));
            return c.result();
        } catch (IllegalArgumentException | ClassCastException e) {
            return ValidationResult.error("cannot parse artefact: " + e.getMessage());
        }
    }

    private ValidationResult decision(ChangeKind kind, String target, String yaml) {
        ValidationResult.Collector c = new ValidationResult.Collector();
        LintReport lint = studio.lint(yaml);
        for (LintReport.LintIssue i : lint.issues()) {
            String msg = (i.ref() == null ? "" : i.ref() + ": ") + i.message();
            if ("error".equals(i.severity())) c.error(msg); else c.warn(msg);
        }
        DecisionDefinitionParser.Parsed parsed = parser.parseLenient(yaml);
        DecisionDefinition def = parsed.definition();
        if (def != null) {
            if (target != null && !target.isBlank() && !def.name().equals(target)) c.error("YAML declares decision '" + def.name() + "' but the change targets '" + target + "'");
            boolean exists = !decisions.versions(def.name()).isEmpty();
            if (kind == ChangeKind.decision_new && exists) c.warn("decision '" + def.name() + "' already exists; the draft becomes its next version");
            if (kind != ChangeKind.decision_new && !exists) c.error("decision '" + def.name() + "' does not exist; use kind decision_new to create it");
            for (RuleDef r : def.rules()) {
                if (r.when() == null) continue;
                for (String issue : cel.validate(r.when())) c.error("rules[" + r.id() + "].when: " + issue);
                Set<String> roots = cel.referencedRoots(r.when());
                if (!roots.isEmpty() && roots.stream().noneMatch(root -> root.equals("input") || root.equals("tenant"))) c.error("rules[" + r.id() + "].when must reference the decision input through the 'input' root (e.g. input.order.total)");
            }
            for (String p : def.preChecks()) for (String issue : cel.validate(p)) c.error("preChecks '" + p + "': " + issue);
            for (String p : def.postChecks()) for (String issue : cel.validate(p)) c.error("postChecks '" + p + "': " + issue);
            if (kind == ChangeKind.decision_bands && def.bands() == null) c.warn("decision_bands change without an explicit bands block; tier defaults apply");
        }
        return c.result();
    }

    private ValidationResult stateModel(String target, String yaml) {
        ValidationResult.Collector c = new ValidationResult.Collector();
        ValidationReport report = stateModels.validate(yaml);
        for (ValidationIssue i : report.issues()) {
            String msg = (i.ref() == null ? "" : i.ref() + ": ") + i.message();
            if (i.isError()) c.error(msg); else c.warn(msg);
        }
        Map<String, Object> m;
        try { m = Json.yamlMap(yaml); } catch (IllegalArgumentException e) { return c.result(); }
        Object id = m.get("stateModel") != null ? m.get("stateModel") : m.get("id");
        if (id == null) c.error("stateModel id is required");
        else {
            if (target != null && !target.isBlank() && !target.equals(String.valueOf(id))) c.error("YAML declares stateModel '" + id + "' but the change targets '" + target + "'");
            int expected = catalogue.nextStateModelVersion(String.valueOf(id));
            int version = m.get("version") instanceof Number n ? n.intValue() : m.get("version") == null ? 1 : Integer.parseInt(String.valueOf(m.get("version")).trim());
            if (version != expected) c.error("version must be " + expected + " (next version of " + id + "), found " + version);
        }
        return c.result();
    }

    private ValidationResult orderType(String target, String text, Set<String> proposed) {
        ValidationResult.Collector c = new ValidationResult.Collector();
        Map<String, Object> m = Json.yamlMap(text);
        if (m.get("orderTypes") instanceof List<?> l && l.size() == 1 && l.get(0) instanceof Map<?, ?> single) m = Json.toMap(single);
        OrderType t = Json.convert(m, OrderType.class);
        if (t.name() == null || !t.name().matches("[a-z][a-z0-9_]*")) c.error("name must be a snake_case identifier");
        else if (target != null && !target.isBlank() && !target.equals(t.name())) c.error("artefact declares order type '" + t.name() + "' but the change targets '" + target + "'");
        if (t.headerStateModel() == null) c.error("headerStateModel is required"); else checkModel(c, t.headerStateModel(), "order", proposed);
        if (t.lineStateModel() == null) c.error("lineStateModel is required"); else checkModel(c, t.lineStateModel(), "order_line", proposed);
        if (t.workflowType() == null || t.workflowType().isBlank()) c.error("workflowType is required");
        else { List<String> types = catalogue.workflowTypes(); if (!types.contains(t.workflowType())) c.warn("workflow type '" + t.workflowType() + "' is not registered in this deployment (registered: " + types + ")"); }
        for (String d : t.decisions()) if (decisions.versions(d).isEmpty() && !proposed.contains("decision:" + d)) c.error("decision '" + d + "' does not exist");
        t.channelDefaults().forEach((ch, pol) -> { if (!POLICIES.contains(pol)) c.error("channelDefaults." + ch + " must be one of " + POLICIES); });
        if (t.returnWindowDays() != null && (t.returnWindowDays() < 0 || t.returnWindowDays() > 3650)) c.error("returnWindowDays must be 0..3650");
        return c.result();
    }

    private void checkModel(ValidationResult.Collector c, String modelId, String entity, Set<String> proposed) {
        if (stateModels.modelIds().contains(modelId)) {
            String actual = stateModels.latest(modelId).entity();
            if (actual != null && !actual.equals(entity)) c.error("state model '" + modelId + "' is for entity '" + actual + "', expected '" + entity + "'");
        } else if (!proposed.contains("state_model:" + modelId)) c.error("state model '" + modelId + "' does not exist (publish it first or propose it in the same change set)");
    }

    @SuppressWarnings("unchecked")
    private ValidationResult sla(String text, Set<String> proposed) {
        ValidationResult.Collector c = new ValidationResult.Collector();
        Map<String, Object> m = Json.yamlMap(text);
        List<Object> entries = m.get("slas") instanceof List<?> l ? (List<Object>) l : m.get("state") != null ? List.of(m) : List.of();
        if (entries.isEmpty()) c.error("expected a 'slas' list");
        Set<String> playbookNames = new java.util.HashSet<>(playbooks.list().stream().map(p -> p.name()).toList());
        int i = 0;
        for (Object o : entries) {
            String ref = "slas[" + i++ + "]";
            if (!(o instanceof Map<?, ?> em)) { c.error(ref + ": must be a mapping"); continue; }
            try {
                SlaDefinition d = new SlaDefinition(null, str(em.get("orderType")), str(em.get("entity")), str(em.get("state")), str(em.get("maxAge")), str(em.get("onBreach")), str(em.get("expectedNextEventWithin")), null);
                if (d.maxAge() == null && d.expectedNextEventWithin() == null) c.error(ref + ": maxAge or expectedNextEventWithin is required");
                if (d.onBreach() != null) {
                    if (!(d.onBreach().startsWith("playbook.") || d.onBreach().startsWith("decision."))) c.error(ref + ": onBreach must be playbook.<name> or decision.<name>");
                    else if (d.onBreach().startsWith("playbook.")) { String p = d.onBreach().substring("playbook.".length()); if (!playbookNames.contains(p) && !proposed.contains("playbook:" + p)) c.warn(ref + ": playbook '" + p + "' does not exist yet"); }
                    else { String dn = d.onBreach().substring("decision.".length()); if (decisions.versions(dn).isEmpty() && !proposed.contains("decision:" + dn)) c.warn(ref + ": decision '" + dn + "' does not exist yet"); }
                }
                if (!Set.of("order", "order_line", "return", "shipment").contains(d.entity())) c.error(ref + ": entity must be order | order_line | return | shipment");
            } catch (RuntimeException e) { c.error(ref + ": " + e.getMessage()); }
        }
        if (m.get("expectedNextEventWithin") instanceof Map<?, ?> w) w.forEach((k, v) -> { try { java.time.Duration.parse(String.valueOf(v)); } catch (Exception e) { c.error("expectedNextEventWithin." + k + " must be an ISO-8601 duration"); } });
        return c.result();
    }

    @SuppressWarnings("unchecked")
    private ValidationResult playbook(String text) {
        ValidationResult.Collector c = new ValidationResult.Collector();
        Map<String, Object> m = Json.yamlMap(text);
        List<Object> entries = m.get("playbooks") instanceof List<?> l ? (List<Object>) l : m.get("name") != null ? List.of(m) : List.of();
        if (entries.isEmpty()) c.error("expected a 'playbooks' list");
        Set<String> actions = new java.util.HashSet<>(catalogue.playbookActions());
        Set<String> queueNames = new java.util.HashSet<>(queues.list().stream().map(TaskQueue::name).toList());
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> pm)) { c.error("playbook entry must be a mapping"); continue; }
            String name = str(pm.get("name"));
            if (name == null || !name.matches("[a-z][a-z0-9_]*")) { c.error("playbook name must be a snake_case identifier"); continue; }
            if (!(pm.get("steps") instanceof List<?> steps) || steps.isEmpty()) { c.error("playbook '" + name + "' needs at least one step"); continue; }
            int i = 0;
            for (Object so : steps) {
                String ref = name + ".steps[" + i++ + "]";
                if (!(so instanceof Map<?, ?> step)) { c.error(ref + ": must be a mapping"); continue; }
                String action = str(step.get("action"));
                if (action == null) c.error(ref + ": action is required");
                else if (!actions.contains(action)) c.error(ref + ": unknown action '" + action + "' (known: " + new java.util.TreeSet<>(actions) + ")");
                if (step.get("minConfidence") != null) { double mc = ((Number) step.get("minConfidence")).doubleValue(); if (mc < 0 || mc > 1) c.error(ref + ": minConfidence must be 0..1"); }
                if (step.get("when") != null) for (String issue : cel.validate(String.valueOf(step.get("when")))) c.error(ref + ".when: " + issue);
                if ("create_task".equals(action)) { String q = str(step.get("queue")); if (q == null) c.error(ref + ": create_task needs a queue"); else if (!queueNames.isEmpty() && !queueNames.contains(q)) c.warn(ref + ": queue '" + q + "' does not exist yet"); }
                if ("hold".equals(action) && step.get("holdType") == null) c.error(ref + ": hold needs a holdType");
                if (step.get("requiresDecision") != null && decisions.versions(String.valueOf(step.get("requiresDecision"))).isEmpty()) c.warn(ref + ": decision '" + step.get("requiresDecision") + "' does not exist");
                for (Object k : step.keySet()) if (!GATE_KEYS.contains(String.valueOf(k))) c.warn(ref + ": unknown step key '" + k + "'");
            }
        }
        if (m.get("spendLimits") != null && !(m.get("spendLimits") instanceof Map)) c.error("spendLimits must be a mapping");
        return c.result();
    }

    private ValidationResult complianceRules(String text) {
        ValidationResult.Collector c = new ValidationResult.Collector();
        var rules = compliance.parse(text);
        if (rules.isEmpty()) c.error("no compliance rules found");
        return c.result();
    }

    private ValidationResult tenantConfig(String text) {
        ValidationResult.Collector c = new ValidationResult.Collector();
        Map<String, Object> m = Json.yamlMap(text);
        if (m.isEmpty()) c.error("no configuration keys found");
        m.forEach((k, v) -> ConfigKeyCatalog.validate(k, v).forEach(c::error));
        return c.result();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
