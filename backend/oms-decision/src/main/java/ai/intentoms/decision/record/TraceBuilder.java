package ai.intentoms.decision.record;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Bands;
import ai.intentoms.decision.model.CheckResult;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionEscalation;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionTrace;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.platform.json.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assembles the FR-XAI-01 "why" trace from a record, its definition version and any escalation. */
@Component
public class TraceBuilder {
    private static final TypeReference<List<CheckResult>> CHECKS = new TypeReference<>() {};
    private final DecisionDefinitionRepository definitions;
    private final DecisionEscalationRepository escalations;

    public TraceBuilder(DecisionDefinitionRepository definitions, DecisionEscalationRepository escalations) {
        this.definitions = definitions;
        this.escalations = escalations;
    }

    public DecisionTrace build(DecisionRecord r) {
        DecisionDefinition def = definitions.find(r.decision(), r.version()).map(DecisionDefinitionRepository.Row::definition).orElse(null);
        Map<String, Object> summary = new LinkedHashMap<>();
        Tier tier = def != null ? def.tier() : Tier.valueOf(String.valueOf(r.details().getOrDefault("tier", "T1")));
        Bands bands = def != null ? def.effectiveBands() : tier.defaultBands();
        List<DecisionTrace.QuestionTrace> questions = new ArrayList<>();
        if (def != null) {
            summary.put("name", def.name());
            summary.put("version", def.version());
            summary.put("owner", def.owner());
            summary.put("tier", def.tier().name());
            summary.put("provider", def.provider());
            summary.put("fallbackProvider", def.fallbackProvider());
            summary.put("timeoutMs", def.timeoutMs());
            summary.put("default", def.dflt());
            summary.put("stateInclude", def.stateInclude());
            summary.put("rules", def.rules());
            summary.put("status", def.status());
            for (QuestionDef q : def.questions().values()) {
                questions.add(new DecisionTrace.QuestionTrace(q.key(), q.type(), q.effectiveInstructions(), q.choiceCriteria(), q.scoreLevels(), q.reasonCode(), r.answers().get(q.key())));
            }
        }
        // Answers synthesised at runtime (composite, topK ranking, L2) that have no question definition.
        for (var e : r.answers().entrySet()) {
            if (def == null || !def.questions().containsKey(e.getKey())) {
                Answer a = e.getValue();
                questions.add(new DecisionTrace.QuestionTrace(e.getKey(), a.type(), "(derived)", Map.of(), List.of(), false, a));
            }
        }
        List<CheckResult> pre = r.details().get("preChecks") == null ? List.of() : Json.convert(r.details().get("preChecks"), CHECKS);
        List<CheckResult> post = r.details().get("postChecks") == null ? List.of() : Json.convert(r.details().get("postChecks"), CHECKS);
        DecisionEscalation esc = r.escalationId() == null ? null : escalations.find(r.escalationId()).orElse(null);
        Map<String, Object> override = null;
        if (r.overriddenAt() != null) {
            override = new LinkedHashMap<>();
            override.put("value", r.overriddenValue());
            override.put("reason", r.overrideReason());
            override.put("by", r.overriddenBy());
            override.put("at", r.overriddenAt());
        }
        return new DecisionTrace(r.id(), r.decision(), r.version(), tier, r.mode(), summary, r.state(), questions, r.answers(), r.rulesFired(), pre, post, bands, r.band(), r.action(),
            r.layer(), r.value(), r.confidence(), r.requiresHuman(), r.reasonCodes(), r.provider(), r.modelVersion(), r.latencyMs(), r.costUsd(), r.actor(), esc, override, r.labels(),
            r.details(), r.createdAt());
    }
}
