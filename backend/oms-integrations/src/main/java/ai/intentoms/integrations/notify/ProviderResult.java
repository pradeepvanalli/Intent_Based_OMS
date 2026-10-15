package ai.intentoms.integrations.notify;

/** Outcome of one provider call: {@code sent} with the provider's message reference, or {@code failed} with the error. */
public record ProviderResult(boolean sent, String providerRef, String error) {
    public static ProviderResult sent(String ref) { return new ProviderResult(true, ref, null); }
    public static ProviderResult failed(String error) { return new ProviderResult(false, null, error); }
}
