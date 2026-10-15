package ai.intentoms.returns.model;

import ai.intentoms.platform.error.ValidationException;

import java.util.Locale;
import java.util.Set;

/** Channels a return may originate from (FR-RET-01). */
public final class ReturnChannel {
    private ReturnChannel() {}
    public static final String WEB = "web";
    public static final String STORE = "store";
    public static final String AGENT = "agent";
    public static final String CS = "cs";
    public static final String UCP = "ucp";
    public static final Set<String> ALL = Set.of(WEB, STORE, AGENT, CS, UCP);

    public static String normalise(String channel) {
        if (channel == null || channel.isBlank()) throw new ValidationException("channel is required (web|store|agent|cs|ucp)");
        String c = channel.trim().toLowerCase(Locale.ROOT);
        if (!ALL.contains(c)) throw new ValidationException("Unknown return channel '" + channel + "'; expected one of " + ALL);
        return c;
    }
}
