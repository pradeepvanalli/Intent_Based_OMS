package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ForbiddenException extends OmsException {
    public ForbiddenException(String message) { super(HttpStatus.FORBIDDEN, "forbidden", message); }
    public ForbiddenException(String message, Map<String, Object> details) { super(HttpStatus.FORBIDDEN, "forbidden", message, details, null); }
}
