package ai.intentoms.finance.payment;

import ai.intentoms.platform.error.OmsException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** Transport-level or unexpected PSP failure (network, 5xx, malformed response). Business declines are results, not exceptions. */
public class PaymentGatewayException extends OmsException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "payment_gateway_error", message, Map.of(), cause);
    }
    public PaymentGatewayException(String message) { this(message, null); }
}
