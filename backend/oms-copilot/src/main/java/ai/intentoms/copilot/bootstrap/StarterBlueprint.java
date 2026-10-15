package ai.intentoms.copilot.bootstrap;

import ai.intentoms.copilot.change.ChangeKind;
import ai.intentoms.copilot.change.ProposedChange;
import ai.intentoms.copilot.change.ValidationResult;
import ai.intentoms.decision.definition.DefinitionEditor;
import ai.intentoms.platform.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic rendering of {@code starter-blueprint.yaml} from a {@link BusinessDescription}: picks the order types for
 * the described channels, fills tenant config placeholders, selects compliance rule groups, SLAs and playbooks, and
 * emits starter decisions (bands applied) and state models as artefacts. Used when no model key is configured and as
 * the baseline the model refines.
 */
final class StarterBlueprint {
    private StarterBlueprint() {}

    @SuppressWarnings("unchecked")
    static TenantBlueprint render(String yaml, BusinessDescription d, Map<String, String> starterDecisions, Map<String, String> starterStateModels) {
        Map<String, Object> bp = Json.yamlMap(yaml);
        List<ProposedChange> changes = new ArrayList<>();
        Map<String, Object> sections = new LinkedHashMap<>();
        int n = 1;

        // Order types per channel
        Map<String, Object> orderTypes = (Map<String, Object>) bp.get("orderTypes");
        List<String> chosenTypes = new ArrayList<>();
        for (String channel : d.channels()) {
            String key = switch (channel) { case "pos" -> "store"; case "online" -> "web"; default -> channel; };
            Object t = orderTypes.get(key);
            if (t == null) continue;
            Map<String, Object> type = new LinkedHashMap<>((Map<String, Object>) t);
            type.put("returnWindowDays", "b2b".equals(key) ? Math.max(d.returnWindowDays(), 60) : d.returnWindowDays());
            String name = String.valueOf(type.get("name"));
            if (chosenTypes.contains(name)) continue;
            chosenTypes.add(name);
            changes.add(change("b" + n++, ChangeKind.order_type, name, "Order type for the " + channel + " channel", Json.toYaml(type), "T1"));
        }
        sections.put("orderTypes", chosenTypes);

        // Channel defaults + margin floor + returns settings
        Map<String, Object> cfg = new LinkedHashMap<>();
        ((Map<String, Object>) bp.get("tenantConfig")).forEach((k, v) -> cfg.put(k, fill(v, d)));
        Map<String, String> channelDefaults = new LinkedHashMap<>();
        for (String ch : d.channels()) channelDefaults.put(ch, switch (ch) { case "marketplace", "b2b", "agent", "pos" -> "ship_complete"; case "store" -> "pickup_consolidated"; case "same_day" -> "earliest_partial"; default -> "ship_as_available"; });
        cfg.put("sourcing.channel_default_policy", channelDefaults);
        changes.add(change("b" + n++, ChangeKind.tenant_config, "tenant_config", "Margin floor, split penalty, return window, restocking fee, keep-it and channel default policies from the business description", Json.toYaml(cfg), "T2"));
        sections.put("channelDefaults", channelDefaults);
        sections.put("marginFloor", d.marginFloor());
        sections.put("returns", Map.of("windowDays", d.returnWindowDays(), "restockingFeePct", d.restockingFeePct(), "keepItEnabled", d.keepItEnabled()));

        // Compliance rule groups
        Map<String, Object> groups = (Map<String, Object>) bp.get("complianceRules");
        List<Object> rules = new ArrayList<>();
        List<String> ruleGroups = new ArrayList<>();
        if (d.hazmat()) { rules.addAll((List<Object>) groups.get("hazmat")); ruleGroups.add("hazmat"); }
        if (d.ageRestricted()) { rules.addAll((List<Object>) groups.get("ageRestricted")); ruleGroups.add("ageRestricted"); }
        if (d.international()) { rules.addAll((List<Object>) groups.get("international")); ruleGroups.add("international"); }
        if (!rules.isEmpty()) changes.add(change("b" + n++, ChangeKind.compliance_rule, "compliance", "Compliance rules for " + ruleGroups, Json.toYaml(Map.of("rules", rules)), "T3"));
        sections.put("complianceRuleGroups", ruleGroups);

        // SLAs
        List<Object> slas = new ArrayList<>((List<Object>) bp.get("slas"));
        if (d.channels().contains("same_day")) slas.addAll((List<Object>) bp.get("sameDaySlas"));
        changes.add(change("b" + n++, ChangeKind.sla, "sla", "Starter SLA definitions per state", Json.toYaml(Map.of("slas", slas)), "T1"));
        sections.put("slas", slas.size());

        // Playbooks
        changes.add(change("b" + n++, ChangeKind.playbook, "playbooks", "Starter remediation playbooks", Json.toYaml(Map.of("playbooks", bp.get("playbooks"))), "T1"));
        sections.put("playbooks", ((List<Object>) bp.get("playbooks")).stream().map(p -> String.valueOf(((Map<?, ?>) p).get("name"))).toList());
        sections.put("exceptionQueues", bp.get("exceptionQueues"));

        // Starter decisions with tier/bands
        List<Map<String, Object>> enabled = new ArrayList<>();
        for (Object o : (List<Object>) bp.get("starterDecisions")) {
            Map<String, Object> sd = (Map<String, Object>) o;
            String name = String.valueOf(sd.get("name"));
            String source = starterDecisions.get(name);
            if (source == null) continue;
            if (name.equals("agent_mandate_match") && !d.channels().contains("agent")) continue;
            Map<String, Object> edits = new LinkedHashMap<>();
            edits.put("tier", sd.get("tier"));
            edits.put("bands.act", sd.get("act"));
            edits.put("bands.confirm", sd.get("confirm"));
            String after = DefinitionEditor.apply(source, edits);
            changes.add(change("b" + n++, ChangeKind.decision_new, name, "Starter decision " + name + " at tier " + sd.get("tier"), after, String.valueOf(sd.get("tier"))));
            enabled.add(Map.of("name", name, "tier", sd.get("tier"), "act", sd.get("act"), "confirm", sd.get("confirm")));
        }
        sections.put("decisions", enabled);

        // State model variants
        Map<String, Object> variants = (Map<String, Object>) bp.get("stateModelVariants");
        List<String> wanted = new ArrayList<>((List<String>) variants.get("ship"));
        wanted.addAll((List<String>) variants.get("returns"));
        if (d.channels().contains("store") || d.channels().contains("pos")) wanted.addAll((List<String>) variants.get("store"));
        List<String> models = new ArrayList<>();
        for (String id : wanted) {
            String y = starterStateModels.get(id);
            if (y == null) continue;
            changes.add(change("b" + n++, ChangeKind.state_model, id, "Starter state model " + id, y, "T2"));
            models.add(id);
        }
        sections.put("stateModelVariants", wanted);
        sections.put("stateModelsProposed", models);
        sections.put("nodeSkeleton", d.nodes().isEmpty() ? List.of(Map.of("id", "DC-01", "type", "dc", "capabilities", List.of("ship")), Map.of("id", "STORE-01", "type", "store", "capabilities", List.of("bopis", "sfs"))) : d.nodes());

        List<String> assumptions = new ArrayList<>();
        assumptions.add("Channels " + d.channels() + " map to order types " + chosenTypes);
        assumptions.add("Margin floor " + d.marginFloor() + ", return window " + d.returnWindowDays() + " days, restocking fee " + d.restockingFeePct());
        if (!d.hazmat()) assumptions.add("No hazardous goods: hazmat compliance rules omitted");
        List<String> questions = new ArrayList<>();
        if (d.nodes().isEmpty()) questions.add("Which fulfilment nodes (DCs, stores, vendors) should be created? A placeholder skeleton is suggested.");
        if (d.channels().size() == 1) questions.add("Only one channel was given; add marketplace, store or agent channels if they apply.");
        String summary = "Starter configuration for a retailer selling through " + String.join(", ", d.channels()) + ": " + chosenTypes.size() + " order type(s), tenant settings, "
            + rules.size() + " compliance rule(s), " + slas.size() + " SLA(s), starter playbooks, " + enabled.size() + " starter decision(s) and " + models.size() + " state model(s), all staged as drafts for review.";
        return new TenantBlueprint(summary, assumptions, questions, sections, changes, false);
    }

    private static Object fill(Object v, BusinessDescription d) {
        if (!(v instanceof String s)) return v;
        return switch (s) {
            case "{marginFloor}" -> d.marginFloor();
            case "{returnWindowDays}" -> d.returnWindowDays();
            case "{restockingFeePct}" -> d.restockingFeePct();
            case "{keepItEnabled}" -> d.keepItEnabled();
            default -> s;
        };
    }

    private static ProposedChange change(String id, ChangeKind kind, String target, String rationale, String after, String tier) {
        return new ProposedChange(id, kind, target, rationale, null, after, null, ValidationResult.passed(), List.of(), tier, 1, false, null, null);
    }
}
