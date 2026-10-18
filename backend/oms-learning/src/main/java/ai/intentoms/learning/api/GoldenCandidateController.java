package ai.intentoms.learning.api;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DF-03: candidate golden cases (situations people resolved the same way again and again, found by
 * {@code GoldenSetGrowthLearner}) become real golden cases only when a reviewer signs them off. Sign-off writes the
 * case into the decision's golden set with the recorded input of one of the source decisions and the agreed answer
 * (or a corrected one), so every later version of the decision is evaluated against it (GR-11).
 */
@RestController
@RequestMapping("/api/v1/learning/golden-candidates")
public class GoldenCandidateController {
    private static final String ACTION = "add_golden_case";

    private final HypothesisRepository hypotheses;
    private final DecisionRecordRepository decisionRecords;
    private final DecisionDefinitionRepository definitions;
    private final GoldenCaseRepository golden;
    private final LearningEvents events;
    private final AuthorizationService authz;

    public GoldenCandidateController(HypothesisRepository hypotheses, DecisionRecordRepository decisionRecords, DecisionDefinitionRepository definitions,
                                     GoldenCaseRepository golden, LearningEvents events, AuthorizationService authz) {
        this.hypotheses = hypotheses;
        this.decisionRecords = decisionRecords;
        this.definitions = definitions;
        this.golden = golden;
        this.events = events;
        this.authz = authz;
    }

    public record SignOff(String expectedValue, String notes) {}

    /** Open candidates, newest first: decision, agreed answer, how many resolutions agreed, and the source records. */
    @GetMapping
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<Map<String, Object>> candidates() {
        return hypotheses.byStatus("OPEN", 200).stream().filter(h -> ACTION.equals(h.lever().get("action"))).map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hypothesisId", h.hypothesisId());
            m.put("decision", h.lever().get("decision"));
            m.put("expectedValue", h.lever().get("expectedValue"));
            m.put("sourceRecordIds", h.lever().get("sourceRecordIds"));
            m.put("agreeingResolutions", h.evidence() == null ? 0 : h.evidence().size());
            m.put("rationale", h.rationale());
            m.put("createdAt", h.createdAt());
            return m;
        }).toList();
    }

    /** Writes the golden case. Needs decision.approve as well: a golden case changes what every future version must pass. */
    @PostMapping("/{hypothesisId}/sign-off")
    @RequiresPermission(value = {Permissions.LEARNING_ADMIN, Permissions.DECISION_APPROVE}, any = false)
    public GoldenCase signOff(@PathVariable String hypothesisId, @RequestBody(required = false) SignOff body) {
        Hypothesis h = candidate(hypothesisId);
        String decision = String.valueOf(h.lever().get("decision"));
        DecisionDefinition def = definitions.byStatus(decision, "live").stream().findFirst()
            .orElseThrow(() -> new ConflictException("Decision " + decision + " has no live version")).definition();
        if (def.evaluation() == null) throw new ConflictException("Decision " + decision + " has no golden set configured");
        DecisionRecord source = sourceRecord(h);
        String expected = body != null && body.expectedValue() != null && !body.expectedValue().isBlank() ? body.expectedValue() : String.valueOf(h.lever().get("expectedValue"));
        String reviewer = authz.requirePrincipal().actorRef();
        String caseId = "learned_" + String.valueOf(h.lever().getOrDefault("inputHash", hypothesisId)).substring(0, Math.min(12, String.valueOf(h.lever().getOrDefault("inputHash", hypothesisId)).length()));
        String notes = "Signed off by " + reviewer + " from " + h.evidence().size() + " agreeing human resolutions" + (body != null && body.notes() != null ? ": " + body.notes() : "");
        GoldenCase gc = new GoldenCase(def.evaluation().goldenSet(), caseId, source.input(), Map.of("value", typed(expected)), notes);
        golden.upsert(gc);
        hypotheses.updateStatus(hypothesisId, Hypothesis.Status.DRAFTED);
        events.hypothesisRaised(hypothesisId, Actor.parse(reviewer), Map.of("action", "golden_case_signed_off", "decision", decision, "goldenSet", gc.goldenSet(), "caseId", caseId));
        return gc;
    }

    @PostMapping("/{hypothesisId}/reject")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public Map<String, Object> reject(@PathVariable String hypothesisId) {
        candidate(hypothesisId);
        hypotheses.updateStatus(hypothesisId, Hypothesis.Status.REJECTED);
        return Map.of("hypothesisId", hypothesisId, "status", "REJECTED");
    }

    private Hypothesis candidate(String hypothesisId) {
        Hypothesis h = hypotheses.find(hypothesisId).orElseThrow(() -> new NotFoundException("Candidate " + hypothesisId + " not found"));
        if (!ACTION.equals(h.lever().get("action"))) throw new NotFoundException("Hypothesis " + hypothesisId + " is not a golden-case candidate");
        if (h.status() != Hypothesis.Status.OPEN) throw new ConflictException("Candidate " + hypothesisId + " is already " + h.status());
        return h;
    }

    private DecisionRecord sourceRecord(Hypothesis h) {
        Object ids = h.lever().get("sourceRecordIds");
        List<?> candidates = ids instanceof List<?> l ? l : h.evidence();
        for (Object id : candidates) {
            var r = decisionRecords.find(String.valueOf(id));
            if (r.isPresent() && r.get().input() != null) return r.get();
        }
        throw new ConflictException("None of the candidate's source decisions are still on record");
    }

    private static Object typed(String v) {
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) {}
        return v;
    }
}
