package ai.intentoms.order.validation;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.config.OrderConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the tenant's ordered validation steps (FR-CAP-05). The order and selection come from
 * {@code tenant_config capture.validation_steps} (default: schema, item_price_sanity, compliance, address,
 * payment_status, agent_provenance); every registered {@link ValidationStep} bean is addressable by name. Unknown names
 * are reported once and skipped so a typo in config cannot silently disable capture. Any blocking issue turns into a
 * {@link ValidationException} whose {@code issues} detail lists everything found (not just the first problem). Steps
 * never call a model (GR-07).
 */
@Component
public class ValidationPipeline {
    private static final Logger log = LoggerFactory.getLogger(ValidationPipeline.class);
    private final Map<String, ValidationStep> steps = new LinkedHashMap<>();
    private final TenantConfigStore config;
    private final Metrics metrics;

    public ValidationPipeline(List<ValidationStep> registered, TenantConfigStore config, Metrics metrics) {
        for (ValidationStep s : registered) steps.put(s.name(), s);
        this.config = config;
        this.metrics = metrics;
    }

    /** Step names in the order they will run for the current tenant. */
    public List<String> configuredSteps() {
        List<Object> configured = config.getList(OrderConfigKeys.VALIDATION_STEPS);
        if (configured.isEmpty()) return OrderConfigKeys.DEFAULT_VALIDATION_STEPS;
        return configured.stream().map(String::valueOf).toList();
    }

    public List<String> availableSteps() { return List.copyOf(steps.keySet()); }

    /** Runs every configured step and returns all issues (blocking and warnings). */
    public List<ValidationIssue> run(CaptureRequest req, ValidationContext ctx) {
        return metrics.time("oms.order.validation", () -> {
            List<ValidationIssue> out = new ArrayList<>();
            for (String name : configuredSteps()) {
                ValidationStep step = steps.get(name);
                if (step == null) {
                    log.warn("capture.validation_steps names unknown step '{}'; available: {}", name, steps.keySet());
                    metrics.count("oms.order.validation.unknown_step", "step", name);
                    continue;
                }
                for (ValidationIssue i : step.validate(req, ctx)) out.add(i.withStep(name));
            }
            return out;
        });
    }

    /** Runs the pipeline and throws when any issue blocks; returns the warnings otherwise. */
    public List<ValidationIssue> validateOrThrow(CaptureRequest req, ValidationContext ctx) {
        List<ValidationIssue> issues = run(req, ctx);
        List<ValidationIssue> blocking = issues.stream().filter(ValidationIssue::blocks).toList();
        if (!blocking.isEmpty()) {
            for (ValidationIssue b : blocking) metrics.count("oms.order.validation.blocked", "step", String.valueOf(b.step()), "code", b.code());
            throw new ValidationException("Order failed validation: " + blocking.get(0).message(),
                Map.of("issues", issues.stream().map(Json::toMap).toList(), "blocking", blocking.size(), "channel", String.valueOf(req.channel()), "channelOrderId", String.valueOf(req.channelOrderId())));
        }
        return issues;
    }
}
