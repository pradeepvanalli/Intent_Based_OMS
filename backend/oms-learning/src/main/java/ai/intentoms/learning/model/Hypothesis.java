package ai.intentoms.learning.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The analyst LLM's only output: a testable claim, never a conclusion (requirements doc §6.2). The analyst never
 * sees raw PII or a single order — only {@code OutcomeDigest} aggregates with a minimum cell size (OC-07's sibling
 * rule in §6.4: default 30 episodes), so it cannot overfit to one order.
 */
public record Hypothesis(String hypothesisId, Map<String, Object> scope, String targetMetric, Direction expectedDirection, Map<String, Object> lever,
                          @DecimalMin("0") @DecimalMax("1") double priorConfidence, List<String> evidence,
                          @Size(max = 280) String rationale, Status status, Instant createdAt) {
    public enum Status { OPEN, DRAFTED, REJECTED, SUPERSEDED }
}
