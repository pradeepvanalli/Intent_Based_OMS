package ai.intentoms.platform.security;

import jakarta.servlet.http.HttpServletRequest;

/** Client address for rate limiting and audit; honours the first {@code X-Forwarded-For} hop only when configured to trust the proxy. */
public final class ClientIp {
    private ClientIp() {}

    public static String of(HttpServletRequest req, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        String addr = req.getRemoteAddr();
        return addr == null ? "unknown" : addr;
    }
}
