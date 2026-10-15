package ai.intentoms.monitoring.exception;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Classifies a raw signal with the T0 decision {@code exception_classify} (FR-MON-03, GR-03): input
 * {@code exception{source, code, message}, order{channel, status}}. The escape option {@code unclear}, an escalated or
 * defaulted result, or a type absent from the taxonomy all resolve to {@code other} (the triage queue) — the model
 * never invents a route.
 */
@Component
public class ExceptionClassifier {
    public static final String DECISION = "exception_classify";
    private final DecisionService decisions;
    private final ExceptionTypeRepository types;
    private final Metrics metrics;

    public record Classification(String type, ExceptionTypeDef definition, DecisionResult result) {}

    public ExceptionClassifier(DecisionService decisions, ExceptionTypeRepository types, Metrics metrics) {
        this.decisions = decisions;
        this.types = types;
        this.metrics = metrics;
    }

    public Classification classify(ExceptionRequest req, Optional<Order> order, Actor actor) {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("source", req.source());
        exception.put("code", req.code());
        exception.put("message", req.message());
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("channel", order.map(Order::channel).orElse("unknown"));
        o.put("status", order.map(Order::status).orElse("unknown"));
        Map<String, Object> input = Map.of("exception", exception, "order", o);
        String subjectType = req.orderId() == null ? "exception" : "order";
        String subjectId = req.orderId() == null ? req.source() + ":" + req.code() : req.orderId();
        DecisionResult r = decisions.decide(DECISION, input, DecisionOptions.live(subjectType, subjectId, actor));
        String type = resolve(r);
        ExceptionTypeDef def = definition(type);
        metrics.count("oms.monitoring.exceptions_classified", "type", def.type(), "layer", r.layer().name(), "action", r.action());
        return new Classification(def.type(), def, r);
    }

    /** Taxonomy entry for a type; unknown types fall back to {@code other}, and a missing {@code other} to a built-in triage default. */
    public ExceptionTypeDef definition(String type) {
        return types.find(type).or(() -> types.find(ExceptionTypeDef.OTHER))
            .orElseGet(() -> new ExceptionTypeDef(ExceptionTypeDef.OTHER, "low", "unclassified", null, 480, "Unclassified"));
    }

    public java.util.List<ExceptionTypeDef> taxonomy() { return types.list(); }

    String resolve(DecisionResult r) {
        if (r == null || !(r.value() instanceof String s) || s.isBlank()) return ExceptionTypeDef.OTHER;
        if (ExceptionTypeDef.UNCLEAR.equals(s)) return ExceptionTypeDef.OTHER;
        if (DecisionResult.ESCALATE.equals(r.action()) || DecisionResult.BLOCKED.equals(r.action()) || DecisionResult.DEFAULT.equals(r.action())) return ExceptionTypeDef.OTHER;
        return types.find(s).isPresent() ? s : ExceptionTypeDef.OTHER;
    }
}
