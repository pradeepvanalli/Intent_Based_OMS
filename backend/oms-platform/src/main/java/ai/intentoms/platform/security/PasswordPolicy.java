package ai.intentoms.platform.security;

import ai.intentoms.platform.error.ValidationException;
import org.springframework.stereotype.Component;

/**
 * Password rules for credentials set through the API (NFR-07). Bootstrap dev users ({@code oms.security.dev-users}) bypass
 * this class on purpose: they are forbidden in production by the readiness check.
 */
@Component
public class PasswordPolicy {
    private final int minLength;

    public PasswordPolicy(SecurityProperties props) { this.minLength = props.passwordMinLength(); }

    public int minLength() { return minLength; }

    public void validate(String password, String userId) {
        if (password == null || password.length() < minLength) throw new ValidationException("Password must be at least " + minLength + " characters");
        if (password.isBlank()) throw new ValidationException("Password must not be blank");
        if (userId != null && password.equalsIgnoreCase(userId)) throw new ValidationException("Password must not equal the user id");
    }
}
