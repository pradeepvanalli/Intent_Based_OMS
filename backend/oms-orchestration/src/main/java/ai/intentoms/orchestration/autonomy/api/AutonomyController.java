package ai.intentoms.orchestration.autonomy.api;

import ai.intentoms.orchestration.autonomy.Anomaly;
import ai.intentoms.orchestration.autonomy.AnomalyRepository;
import ai.intentoms.orchestration.autonomy.AnomalyScanner;
import ai.intentoms.orchestration.autonomy.AutonomousAction;
import ai.intentoms.orchestration.autonomy.AutonomousActionRepository;
import ai.intentoms.orchestration.autonomy.AutonomousActionType;
import ai.intentoms.orchestration.autonomy.AutonomyConfigKeys;
import ai.intentoms.orchestration.autonomy.AutonomyUndoService;
import ai.intentoms.orchestration.autonomy.GuardrailConfig;
import ai.intentoms.orchestration.autonomy.GuardrailService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Agentic Control Tower REST API (FR-MON-05 sibling): the anomaly feed, the autonomous-action audit log and its
 * undo, the guardrail configuration (kill switch, caps, allow-list, confidence override) and today's stats.
 * Read endpoints use {@code exception.read} (the same ops-visibility permission as the rest of the control tower);
 * undo uses {@code exception.work}; guardrail changes, the kill switch and an ad-hoc scan are {@code config.admin}
 * because they change tenant-wide behaviour, not just one order.
 */
@RestController
@RequestMapping("/api/v1/autonomy")
public class AutonomyController {
    private final AnomalyRepository anomalies;
    private final AutonomousActionRepository actionsRepo;
    private final AutonomyUndoService undo;
    private final GuardrailService guardrailService;
    private final TenantConfigStore config;
    private final AnomalyScanner scanner;
    private final AuthorizationService authz;

    public AutonomyController(AnomalyRepository anomalies, AutonomousActionRepository actionsRepo, AutonomyUndoService undo, GuardrailService guardrailService,
                              TenantConfigStore config, AnomalyScanner scanner, AuthorizationService authz) {
        this.anomalies = anomalies;
        this.actionsRepo = actionsRepo;
        this.undo = undo;
        this.guardrailService = guardrailService;
        this.config = config;
        this.scanner = scanner;
        this.authz = authz;
    }

    public record UndoRequest(String reason) {}
    public record KillSwitchRequest(boolean enabled) {}
    public record GuardrailsUpdateRequest(Boolean enabled, Integer maxActionsPerDay, BigDecimal maxUsdImpactPerAction, List<String> allowedActions, Double minConfidenceOverride) {}
    public record GuardrailsView(boolean enabled, boolean killSwitch, int maxActionsPerDay, BigDecimal maxUsdImpactPerAction, List<String> allowedActions, Double minConfidenceOverride) {
        static GuardrailsView of(GuardrailConfig c) { return new GuardrailsView(c.enabled(), c.killSwitch(), c.maxActionsPerDay(), c.maxUsdImpactPerAction(), c.allowedActions().stream().sorted().toList(), c.minConfidenceOverride()); }
    }
    public record AutonomyStats(int actionsToday, int maxActionsPerDay, BigDecimal usdImpactResolvedToday, BigDecimal maxUsdImpactPerAction, int humanEscalations, double automationRate) {}
    public record ScanResult(int anomaliesFound, List<Anomaly> anomalies) {}

    @GetMapping("/anomalies")
    @RequiresPermission(Permissions.EXCEPTION_READ)
    public Page<Anomaly> anomalies(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return anomalies.list(offset, limit);
    }

    @GetMapping("/actions")
    @RequiresPermission(Permissions.EXCEPTION_READ)
    public Page<AutonomousAction> actions(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String actionType, @RequestParam(required = false) String outcome,
                                          @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return actionsRepo.list(parseInstant(from), parseInstant(to), actionType, outcome, offset, limit);
    }

    @PostMapping("/actions/{id}/undo")
    @RequiresPermission(Permissions.EXCEPTION_WORK)
    public AutonomousAction undoAction(@PathVariable String id, @RequestBody(required = false) UndoRequest body) {
        String reason = body == null || body.reason() == null || body.reason().isBlank() ? "manual undo" : body.reason();
        return undo.undo(id, reason, Actor.parse(authz.actorRef()));
    }

    @GetMapping("/guardrails")
    @RequiresPermission(Permissions.EXCEPTION_READ)
    public GuardrailsView guardrails() { return GuardrailsView.of(guardrailService.load()); }

    @PutMapping("/guardrails")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public GuardrailsView updateGuardrails(@RequestBody GuardrailsUpdateRequest body) {
        if (body.enabled() != null) config.set(AutonomyConfigKeys.ENABLED, body.enabled());
        if (body.maxActionsPerDay() != null) {
            if (body.maxActionsPerDay() < 0) throw new ValidationException("maxActionsPerDay must be >= 0");
            config.set(AutonomyConfigKeys.MAX_ACTIONS_PER_DAY, body.maxActionsPerDay());
        }
        if (body.maxUsdImpactPerAction() != null) {
            if (body.maxUsdImpactPerAction().signum() < 0) throw new ValidationException("maxUsdImpactPerAction must be >= 0");
            config.set(AutonomyConfigKeys.MAX_USD_IMPACT_PER_ACTION, body.maxUsdImpactPerAction());
        }
        if (body.allowedActions() != null) {
            for (String code : body.allowedActions()) {
                String lc = code.toLowerCase(Locale.ROOT);
                if (!AutonomousActionType.isKnownCode(lc)) throw new ValidationException("Unknown action type '" + code + "'");
                if (AutonomousActionType.NEVER_AUTONOMOUS.contains(AutonomousActionType.fromCode(lc))) throw new ValidationException("'" + code + "' is never autonomous and cannot be added to the allow-list");
            }
            config.set(AutonomyConfigKeys.ALLOWED_ACTIONS, body.allowedActions());
        }
        if (body.minConfidenceOverride() != null) {
            if (body.minConfidenceOverride() < 0 || body.minConfidenceOverride() > 1) throw new ValidationException("minConfidenceOverride must be between 0 and 1");
            config.set(AutonomyConfigKeys.MIN_CONFIDENCE_OVERRIDE, body.minConfidenceOverride());
        }
        return GuardrailsView.of(guardrailService.load());
    }

    @PostMapping("/kill-switch")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public GuardrailsView killSwitch(@RequestBody KillSwitchRequest body) {
        config.set(AutonomyConfigKeys.KILL_SWITCH, body.enabled());
        return GuardrailsView.of(guardrailService.load());
    }

    @GetMapping("/stats")
    @RequiresPermission(Permissions.EXCEPTION_READ)
    public AutonomyStats stats() {
        GuardrailConfig cfg = guardrailService.load();
        int actionsToday = actionsRepo.countExecutedToday();
        int humanEscalations = anomalies.countEscalatedToday();
        int total = actionsToday + humanEscalations;
        double automationRate = total == 0 ? 0.0 : (double) actionsToday / total;
        return new AutonomyStats(actionsToday, cfg.maxActionsPerDay(), actionsRepo.sumEstimatedImpactUsdToday(), cfg.maxUsdImpactPerAction(), humanEscalations, automationRate);
    }

    @PostMapping("/scan-now")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public ScanResult scanNow() {
        List<Anomaly> found = scanner.scanTenant();
        return new ScanResult(found.size(), found);
    }

    private static Instant parseInstant(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v); } catch (java.time.format.DateTimeParseException e) { return java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
    }
}
