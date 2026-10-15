package ai.intentoms.finance.compliance;

import ai.intentoms.platform.error.ValidationException;

import java.util.Set;

/**
 * A tenant-authored L0 compliance rule (FR-FIN-03) evaluated with CEL over roots {@code line}, {@code order}
 * (destination, customer, ship_method) and {@code tenant} (configuration such as {@code compliance_restricted_zips}).
 *
 * @param id         stable rule id (unique per tenant)
 * @param name       display name
 * @param scope      {@code line} (evaluated once per line) or {@code order} (evaluated once with an empty {@code line})
 * @param when       CEL boolean expression
 * @param action     {@code block} | {@code hold} | {@code require_signature}
 * @param reasonCode machine-readable code emitted on violations
 * @param severity   {@code error} | {@code warning}
 * @param message    message template; placeholders {@code {sku}}, {@code {lineId}}, {@code {state}}, {@code {zip}}, {@code {shipMethod}}
 * @param priority   evaluation order (lower first)
 * @param active     inactive rules are kept but never evaluated
 */
public record ComplianceRule(String id, String name, String scope, String when, String action, String reasonCode, String severity, String message, int priority, boolean active) {
    public static final Set<String> ACTIONS = Set.of("block", "hold", "require_signature");
    public static final Set<String> SCOPES = Set.of("line", "order");
    public static final Set<String> SEVERITIES = Set.of("error", "warning");

    public ComplianceRule {
        if (id == null || id.isBlank()) throw new ValidationException("compliance rule id is required");
        if (when == null || when.isBlank()) throw new ValidationException("compliance rule '" + id + "' needs a 'when' expression");
        scope = scope == null || scope.isBlank() ? "line" : scope;
        severity = severity == null || severity.isBlank() ? "error" : severity;
        action = action == null || action.isBlank() ? "block" : action;
        name = name == null || name.isBlank() ? id : name;
        reasonCode = reasonCode == null || reasonCode.isBlank() ? id.toUpperCase().replace('-', '_') : reasonCode;
        if (!ACTIONS.contains(action)) throw new ValidationException("compliance rule '" + id + "': unknown action '" + action + "'");
        if (!SCOPES.contains(scope)) throw new ValidationException("compliance rule '" + id + "': unknown scope '" + scope + "'");
        if (!SEVERITIES.contains(severity)) throw new ValidationException("compliance rule '" + id + "': unknown severity '" + severity + "'");
    }

    public boolean orderScoped() { return "order".equals(scope); }
}
