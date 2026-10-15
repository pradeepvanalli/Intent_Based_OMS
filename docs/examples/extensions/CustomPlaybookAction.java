package com.example.oms.extensions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.order.model.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Example playbook step: open a ticket in an external help-desk when an exception needs a human (FR-MON-04).
 *
 * <p>How plugging in works: any Spring {@code @Component} implementing {@link PlaybookAction} is picked up by
 * {@code PlaybookService}; the value of {@link #action()} is the {@code action} key you then use in
 * {@code playbooks.yaml}:
 * <pre>
 * playbooks:
 *   - name: address_fix
 *     steps:
 *       - { action: hold, holdType: address, blocks: [release] }
 *       - { action: open_ticket, queueName: "CS-Addresses", minConfidence: 0.0 }
 * </pre>
 * Gates ({@code minConfidence}, {@code requiresDecision}, {@code when} CEL, {@code onFailure}) are evaluated by the
 * service before {@link #run} is called; spend limits come through {@link PlaybookContext#spendLimit(String)}.
 * Return {@code done}, {@code skipped} or {@code failed}; never throw for business outcomes (a thrown exception is
 * recorded as {@code failed} and never breaks the playbook run).
 *
 * <p>Build: put this class in a module that depends on {@code ai.intentoms:oms-monitoring} and {@code oms-order}
 * (or in {@code oms-orchestration}), under a package scanned by the application ({@code ai.intentoms.*} or add your
 * package to {@code @SpringBootApplication(scanBasePackages = ...)} in {@code oms-app}).
 */
@Component
public class CustomPlaybookAction implements PlaybookAction {
    private final RestClient http;
    private final String helpdeskUrl;

    public CustomPlaybookAction(RestClient.Builder builder, org.springframework.core.env.Environment env) {
        this.http = builder.build();
        this.helpdeskUrl = env.getProperty("example.helpdesk.url", "");
    }

    @Override
    public String action() { return "open_ticket"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        if (helpdeskUrl.isBlank()) return PlaybookStepResult.skipped("example.helpdesk.url not configured");
        String queue = String.valueOf(step.getOrDefault("queueName", "OMS"));
        Optional<Order> order = ctx.order();
        Map<String, Object> ticket = Map.of(
            "queue", queue,
            "title", ctx.exception().type() + " on order " + ctx.orderId(),
            "body", ctx.exception().message(),
            "orderId", ctx.orderId() == null ? "" : ctx.orderId(),
            "channel", order.map(Order::channel).orElse("unknown"),
            // The classification decision and its confidence are available for the ticket text; the trace id lets
            // the agent open the Why drawer. Never copy customer PII into an external system without need.
            "classificationRecordId", ctx.exception().classificationRecordId() == null ? "" : ctx.exception().classificationRecordId(),
            "confidence", ctx.confidence());
        try {
            Map<?, ?> created = http.post().uri(helpdeskUrl).body(ticket).retrieve().body(Map.class);
            String id = created == null ? "?" : String.valueOf(created.get("id"));
            return PlaybookStepResult.done("ticket " + id + " opened in " + queue, Map.of("ticketId", id));
        } catch (RuntimeException e) {
            // Transport problems are a failed step: the playbook's `onFailure: true` steps will run (e.g. create_task).
            return PlaybookStepResult.failed("help-desk call failed: " + e.getMessage());
        }
    }
}
