package ai.intentoms.decision.provider;

/** A provider call failed. {@code retryable} marks 429/529/timeouts; {@code status} is the HTTP status when known. */
public class ProviderException extends RuntimeException {
    private final boolean retryable;
    private final int status;
    private final boolean timeout;

    public ProviderException(String message, boolean retryable, int status, boolean timeout, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
        this.timeout = timeout;
    }

    public static ProviderException timeout(String provider, int budgetMs) { return new ProviderException(provider + " exceeded budget of " + budgetMs + " ms", true, 0, true, null); }
    public static ProviderException status(String provider, int status, String body) {
        boolean retry = status == 429 || status == 529 || status == 503;
        return new ProviderException(provider + " returned HTTP " + status + (body == null || body.isBlank() ? "" : ": " + abbreviate(body)), retry, status, false, null);
    }
    public static ProviderException of(String provider, String message, Throwable cause) { return new ProviderException(provider + ": " + message, false, 0, false, cause); }

    private static String abbreviate(String s) { return s.length() > 300 ? s.substring(0, 300) + "..." : s; }

    public boolean retryable() { return retryable; }
    public int status() { return status; }
    public boolean isTimeout() { return timeout; }
}
