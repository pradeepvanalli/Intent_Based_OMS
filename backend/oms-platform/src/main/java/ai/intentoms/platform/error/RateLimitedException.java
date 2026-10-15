package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class RateLimitedException extends OmsException {
    public RateLimitedException(String message) { super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message); }
    public RateLimitedException(String message, Map<String, Object> details) { super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message, details, null); }
}
