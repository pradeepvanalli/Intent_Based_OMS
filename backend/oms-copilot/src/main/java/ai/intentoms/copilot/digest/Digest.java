package ai.intentoms.copilot.digest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Margin Guardian digest: findings over the last {@code days} with a paste-ready configuration prompt each.
 * {@code period} is {@code {days, from, to}}; {@code facts} is the deterministic fact set the findings were written from.
 */
public record Digest(String id, Map<String, Object> period, String headline, List<Finding> findings, boolean generated, String model, Map<String, Object> facts, Instant generatedAt, String fallbackReason) {
    public record Finding(String title, String metric, String value, String delta, String evidence, String suggestedPrompt) {}
}
