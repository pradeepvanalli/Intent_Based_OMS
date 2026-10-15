package ai.intentoms.copilot.cs;

import java.util.List;
import java.util.Map;

/**
 * CS reply draft (FR-CS-05, GR-15): the text, the fact keys it used, typed slots (promiseDate, refundAmount, status,
 * trackingUrl) filled only from the order, the request classification from {@code customer_request_intent} ({@code intent.decisionRecordId} resolves via /decision-records/{id}/why), and a disclaimer.
 */
public record CsDraft(String draft, List<String> factsUsed, Map<String, Object> slots, String disclaimer, Map<String, Object> intent,
                      boolean generated, String model, String fallbackReason, Map<String, Object> facts) {}
