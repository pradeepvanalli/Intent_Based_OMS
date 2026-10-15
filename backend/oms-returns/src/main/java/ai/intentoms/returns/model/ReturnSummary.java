package ai.intentoms.returns.model;

import ai.intentoms.finance.model.Money;

import java.time.Instant;

/** List row for returns and for the order view's {@code returns[]} (UI contract {@code ReturnSummary}). */
public record ReturnSummary(String rma, String orderId, String status, Instant createdAt, String channel, String customerRef, int lineCount,
                            Money refundAmount, Double riskScore, String riskLevel, String refundTiming, String disposition, Instant updatedAt) {}
