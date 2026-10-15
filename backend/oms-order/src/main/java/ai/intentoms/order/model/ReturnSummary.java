package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.time.Instant;

/** Return as shown on the order view (FR-CS-01); supplied by the returns module through {@code ReturnSummaryProvider}. */
public record ReturnSummary(String rma, String orderId, String status, Instant createdAt, String channel, Integer lineCount, Money refundAmount,
                            Double riskScore, String refundTiming) {}
