package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ValidationException extends OmsException {
    public ValidationException(String message) { super(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", message); }
    public ValidationException(String message, Map<String, Object> details) { super(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", message, details, null); }
}
