package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class UnauthorizedException extends OmsException {
    public UnauthorizedException(String message) { super(HttpStatus.UNAUTHORIZED, "unauthorized", message); }
    public UnauthorizedException(String message, Map<String, Object> details) { super(HttpStatus.UNAUTHORIZED, "unauthorized", message, details, null); }
}
