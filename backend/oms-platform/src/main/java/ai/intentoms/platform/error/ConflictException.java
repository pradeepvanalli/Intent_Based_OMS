package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ConflictException extends OmsException {
    public ConflictException(String message) { super(HttpStatus.CONFLICT, "conflict", message); }
    public ConflictException(String message, Map<String, Object> details) { super(HttpStatus.CONFLICT, "conflict", message, details, null); }
}
