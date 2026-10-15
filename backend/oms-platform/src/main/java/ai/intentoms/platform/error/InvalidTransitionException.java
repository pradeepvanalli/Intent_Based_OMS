package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class InvalidTransitionException extends OmsException {
    public InvalidTransitionException(String message) { super(HttpStatus.CONFLICT, "invalid_transition", message); }
    public InvalidTransitionException(String message, Map<String, Object> details) { super(HttpStatus.CONFLICT, "invalid_transition", message, details, null); }
}
