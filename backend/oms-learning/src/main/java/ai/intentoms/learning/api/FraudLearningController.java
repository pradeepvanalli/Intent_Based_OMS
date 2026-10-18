package ai.intentoms.learning.api;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.learner.FraudDriftMonitor;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fraud learning read and run endpoints (FD-06, FD-07), separate from {@code LearningController} so the fraud work
 * owns its own file: {@code GET /api/v1/learning/fraud/orders/{orderId}/labels} shows each fraud decision of an order
 * with its ground-truth label, and {@code POST /api/v1/learning/fraud/drift/check} runs the drift check now.
 */
@RestController
@RequestMapping("/api/v1/learning/fraud")
public class FraudLearningController {
    private static final Set<String> FRAUD_DECISIONS = Set.of("payment_risk", "rto_risk", "return_risk", "address_risk");

    private final DecisionRecordRepository decisionRecords;
    private final FraudDriftMonitor drift;

    public FraudLearningController(DecisionRecordRepository decisionRecords, FraudDriftMonitor drift) {
        this.decisionRecords = decisionRecords;
        this.drift = drift;
    }

    @GetMapping("/orders/{orderId}/labels")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<Map<String, Object>> labels(@PathVariable String orderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DecisionRecord r : decisionRecords.forSubject("order", orderId)) {
            if (!FRAUD_DECISIONS.contains(r.decision())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordId", r.id());
            m.put("decision", r.decision());
            m.put("version", r.version());
            m.put("layer", r.layer() == null ? null : r.layer().name());
            m.put("action", r.action());
            m.put("value", r.value());
            m.put("confidence", r.confidence());
            m.put("rulesFired", r.rulesFired());
            m.put("label", r.labels() == null ? null : r.labels().get("fraud_outcome"));
            m.put("learnedFacts", r.details() == null ? List.of() : r.details().getOrDefault("learnedFacts", List.of()));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/drift/check")
    @RequiresPermission(Permissions.LEARNING_ADMIN)
    public FraudDriftMonitor.DriftReport checkDrift() { return drift.checkTenant(); }
}
