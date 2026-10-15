package ai.intentoms.order.model;

import java.util.List;

/**
 * Buyer fulfilment intent normalised by the Jev Choice `fulfilment_intent` (FR-CAP-06).
 * policy: ship_complete | ship_as_available | earliest_partial | pickup_consolidated | unclear.
 * source: explicit (buyer chose) | inferred (decision) | channel_default.
 */
public record IntentProfile(String policy, double confidence, String source, List<String> explicitChoices, String decisionRecordId, Boolean splitOk, Boolean isGift) {
    public static IntentProfile unclear(List<String> explicitChoices) { return new IntentProfile("unclear", 0, "pending", explicitChoices == null ? List.of() : explicitChoices, null, null, null); }
}
