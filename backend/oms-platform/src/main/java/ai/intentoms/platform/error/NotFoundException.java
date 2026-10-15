package ai.intentoms.platform.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class NotFoundException extends OmsException {
    public NotFoundException(String message) { super(HttpStatus.NOT_FOUND, "not_found", message); }
    public NotFoundException(String message, Map<String, Object> details) { super(HttpStatus.NOT_FOUND, "not_found", message, details, null); }
}
