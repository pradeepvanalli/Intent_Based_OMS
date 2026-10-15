package ai.intentoms.copilot.llm;

import ai.intentoms.platform.error.OmsException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** The model endpoint failed, timed out, or returned output that does not conform to the requested schema. */
public class LlmException extends OmsException {
    public LlmException(String message) { super(HttpStatus.BAD_GATEWAY, "llm_failed", message); }
    public LlmException(String message, Map<String, Object> details, Throwable cause) { super(HttpStatus.BAD_GATEWAY, "llm_failed", message, details, cause); }
}
