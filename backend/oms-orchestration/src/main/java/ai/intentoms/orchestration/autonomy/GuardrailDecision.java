package ai.intentoms.orchestration.autonomy;

/** Result of {@link GuardrailService#evaluate}: either autonomous execution is allowed, or {@code reason} names exactly which guardrail blocked it (shown to ops in the escalated-task payload and the UI). */
public record GuardrailDecision(boolean allowed, String reason) {
    public static GuardrailDecision allow() { return new GuardrailDecision(true, null); }
    public static GuardrailDecision block(String reason) { return new GuardrailDecision(false, reason); }
}
