package ai.intentoms.copilot.change;

import java.util.Arrays;
import java.util.List;

/** Artefact kinds the configuration copilot may propose (FR-CFG-01). Serialised in snake_case as written here. */
public enum ChangeKind {
    decision_rule, decision_criteria, decision_bands, decision_new, state_model, order_type, sla, playbook, tenant_config, compliance_rule;

    public boolean isDecision() { return this == decision_rule || this == decision_criteria || this == decision_bands || this == decision_new; }

    public static List<String> names() { return Arrays.stream(values()).map(Enum::name).toList(); }

    public static ChangeKind parse(String s) {
        try { return valueOf(s.trim().toLowerCase()); }
        catch (IllegalArgumentException e) { throw new ai.intentoms.platform.error.ValidationException("Unknown change kind '" + s + "'; expected one of " + names()); }
    }
}
