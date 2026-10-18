package ai.intentoms.decision.studio;

import java.time.Instant;
import java.util.Map;

/**
 * DF-06: one version of the prompt text a decision question sends to the model ({@code instructions} for choice and score
 * questions, {@code statement} for yes/no questions). {@code studioVersion} is the decision-definition draft that carries
 * this text through the studio's lint, golden-set, approval and publish gate.
 */
public record PromptVersion(String decision, String question, int version, String text, String status, String changeReason, String ticket,
                            Map<String, Object> lintReport, Map<String, Object> evaluationReport, Integer studioVersion, String createdBy,
                            Instant createdAt, Instant updatedAt, Instant publishedAt) {}
