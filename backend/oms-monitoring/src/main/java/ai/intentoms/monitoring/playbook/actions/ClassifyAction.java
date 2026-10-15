package ai.intentoms.monitoring.playbook.actions;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.monitoring.exception.ExceptionClassifier;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionRepository;
import ai.intentoms.monitoring.exception.ExceptionRequest;
import ai.intentoms.monitoring.exception.ExceptionTypeDef;
import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Built-in {@code classify} step (FR-MON-03/04): re-runs {@code exception_classify} (e.g. in {@code diagnose_stuck}) and,
 * when the type changes to a known taxonomy type, re-routes the exception (type, severity, queue, SLA) while keeping the
 * running playbook.
 */
@Component
public class ClassifyAction implements PlaybookAction {
    private final ExceptionClassifier classifier;
    private final ExceptionRepository exceptions;
    private final TimeSource time;

    public ClassifyAction(ExceptionClassifier classifier, ExceptionRepository exceptions, TimeSource time) {
        this.classifier = classifier;
        this.exceptions = exceptions;
        this.time = time;
    }

    @Override public String action() { return "classify"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        ExceptionRecord e = exceptions.get(ctx.exception().id());
        ExceptionRequest req = new ExceptionRequest(e.source(), e.code(), e.message(), e.orderId(), e.lineId(), e.nodeId(), e.details());
        ExceptionClassifier.Classification c = classifier.classify(req, ctx.order(), ctx.actor());
        DecisionResult r = c.result();
        String detail = "classified as " + c.type() + " (confidence " + Math.round(r.confidence() * 100) / 100.0 + ", record " + r.recordId() + ")";
        if (c.type().equals(e.type()) || ExceptionTypeDef.OTHER.equals(c.type())) return PlaybookStepResult.done(detail + "; type unchanged", Map.of("recordId", r.recordId()));
        ExceptionTypeDef def = c.definition();
        ExceptionRecord updated = e.withType(def.type(), def.severity(), def.queue(), e.playbook(), time.now().plus(Duration.ofMinutes(def.slaMinutes())), r.recordId(), r.confidence());
        exceptions.update(updated);
        return PlaybookStepResult.done(detail + "; re-routed from " + e.type() + " to " + def.type() + " (" + def.queue() + ")", Map.of("recordId", r.recordId(), "type", def.type()));
    }
}
