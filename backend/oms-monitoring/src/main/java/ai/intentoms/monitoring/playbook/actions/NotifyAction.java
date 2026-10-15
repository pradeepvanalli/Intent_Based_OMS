package ai.intentoms.monitoring.playbook.actions;

import ai.intentoms.monitoring.alert.Alert;
import ai.intentoms.monitoring.alert.AlertOutcome;
import ai.intentoms.monitoring.alert.AlertService;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Built-in {@code notify} step (FR-MON-04/06): raises an alert through {@link AlertService}. Parameters: {@code channel}
 * (slack|teams|email|pagerduty; omitted = every configured channel), {@code template} (names the message variant and is
 * part of the de-dup key), {@code severity} (defaults to the exception's).
 */
@Component
public class NotifyAction implements PlaybookAction {
    private final AlertService alerts;

    public NotifyAction(AlertService alerts) { this.alerts = alerts; }

    @Override public String action() { return "notify"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        ExceptionRecord e = ctx.exception();
        String template = step.get("template") == null ? e.type() : String.valueOf(step.get("template"));
        String severity = step.get("severity") == null ? e.severity() : String.valueOf(step.get("severity"));
        Set<String> channels = step.get("channel") == null ? Set.of() : Set.of(String.valueOf(step.get("channel")));
        String subject = e.orderId() == null ? e.id() : e.orderId();
        Map<String, String> links = new LinkedHashMap<>();
        links.put("exception", "/exceptions/" + e.id());
        if (e.orderId() != null) links.put("order", "/orders/" + e.orderId());
        String title = "[" + severity.toUpperCase() + "] " + e.type().replace('_', ' ') + (e.orderId() == null ? "" : " on order " + e.orderId());
        String body = template + ": " + e.message() + (e.nodeId() == null ? "" : " (node " + e.nodeId() + ")");
        AlertOutcome out = alerts.alert(new Alert("exception:" + e.type() + ":" + subject + ":" + template, severity, title, body, links, channels));
        if (out.deduplicated()) return PlaybookStepResult.done("suppressed as duplicate of alert " + out.alertId(), Map.of("alertId", out.alertId()));
        String detail = out.channelsSent().isEmpty() ? "recorded (no channel configured)" : "sent via " + String.join(",", out.channelsSent());
        if (!out.failures().isEmpty()) detail += "; failed: " + String.join(",", out.failures());
        return PlaybookStepResult.done(detail, Map.of("alertId", out.alertId()));
    }
}
