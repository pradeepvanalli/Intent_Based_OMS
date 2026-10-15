package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Base runtime exception mapped to RFC 9457 problem details by {@link ApiExceptionHandler}. */
public class OmsException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public OmsException(HttpStatus status, String code, String message) { this(status, code, message, Map.of(), null); }

    public OmsException(HttpStatus status, String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
