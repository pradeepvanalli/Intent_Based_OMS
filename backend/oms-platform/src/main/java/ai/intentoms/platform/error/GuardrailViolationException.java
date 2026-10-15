package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class GuardrailViolationException extends OmsException {
    public GuardrailViolationException(String message) { super(HttpStatus.UNPROCESSABLE_ENTITY, "guardrail_violation", message); }
    public GuardrailViolationException(String message, Map<String, Object> details) { super(HttpStatus.UNPROCESSABLE_ENTITY, "guardrail_violation", message, details, null); }
}
