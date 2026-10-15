package ai.intentoms.finance.payment;

import ai.intentoms.finance.config.FinanceProperties;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory gateway used in development, tests and demos (FR-FIN-01). It talks to nothing
 * external and never stores card data; behaviour is driven entirely by the tender token suffix:
 * <ul>
 *   <li>{@code *_decline}: authorisation is declined with {@code card_declined};</li>
 *   <li>{@code *_expired}: authorisation succeeds but every capture fails with {@code auth_expired}
 *       (exercises the re-authorise path);</li>
 *   <li>{@code *_refund_fail}: refunds fail with {@code refund_rejected};</li>
 *   <li>anything else succeeds.</li>
 * </ul>
 * PSP references are derived from OMS ids so assertions and replays are stable: {@code mock_auth_<paymentId>},
 * {@code mock_reauth_<paymentId>}, {@code mock_cap_<captureId>}, {@code mock_re_<refundId>}.
 */
@Component
@ConditionalOnProperty(name = "oms.finance.psp", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {
    public static final String NAME = "mock";
    public static final String AUTH_PREFIX = "mock_auth_";
    public static final String REAUTH_PREFIX = "mock_reauth_";
    public static final String CAPTURE_PREFIX = "mock_cap_";
    public static final String REFUND_PREFIX = "mock_re_";
    public static final String DECLINE_SUFFIX = "_decline";
    public static final String EXPIRED_SUFFIX = "_expired";
    public static final String REFUND_FAIL_SUFFIX = "_refund_fail";

    private final TimeSource time;
    private final FinanceProperties props;
    /** pspRef → tender token, so capture/refund behaviour can follow the token suffix without any persistence. */
    private final Map<String, String> tokensByRef = new ConcurrentHashMap<>();

    public MockPaymentGateway(TimeSource time, FinanceProperties props) {
        this.time = time;
        this.props = props;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public AuthResult authorize(AuthRequest r) {
        String token = r.tenderToken() == null ? "" : r.tenderToken();
        if (token.endsWith(DECLINE_SUFFIX)) {
            return AuthResult.declined("card_declined", "The tender was declined by the mock issuer", Map.of("token_suffix", DECLINE_SUFFIX));
        }
        if (r.amount().isNegative() || r.amount().isZero()) {
            return AuthResult.declined("invalid_amount", "Authorisation amount must be positive", Map.of());
        }
        String ref = AUTH_PREFIX + r.paymentId();
        tokensByRef.put(ref, token);
        return AuthResult.approved(ref, time.now().plus(props.mock().authTtl()), Map.of("tender_type", r.tenderType()));
    }

    @Override
    public AuthResult reauthorize(String pspRef, Money amount) {
        String token = tokensByRef.getOrDefault(pspRef, "");
        String paymentId = stripPrefix(pspRef);
        String ref = REAUTH_PREFIX + paymentId;
        tokensByRef.put(ref, token);
        return AuthResult.approved(ref, time.now().plus(props.mock().authTtl()), Map.of("previous_ref", pspRef));
    }

    @Override
    public CaptureResult capture(String pspRef, Money amount, String idempotencyKey) {
        String token = tokensByRef.getOrDefault(pspRef, "");
        if (token.endsWith(EXPIRED_SUFFIX)) {
            return CaptureResult.failed("auth_expired", "The authorisation has expired and must be re-authorised");
        }
        return CaptureResult.succeeded(CAPTURE_PREFIX + idempotencyKey, amount);
    }

    @Override
    public RefundResult refund(String pspRef, Money amount, String idempotencyKey, String reason) {
        String token = tokensByRef.getOrDefault(pspRef, "");
        if (token.endsWith(REFUND_FAIL_SUFFIX)) {
            return RefundResult.failed("refund_rejected", "The mock issuer rejected the refund");
        }
        return RefundResult.succeeded(REFUND_PREFIX + idempotencyKey);
    }

    @Override
    public void voidAuth(String pspRef) { tokensByRef.remove(pspRef); }

    private static String stripPrefix(String pspRef) {
        if (pspRef == null) return "";
        if (pspRef.startsWith(REAUTH_PREFIX)) return pspRef.substring(REAUTH_PREFIX.length());
        if (pspRef.startsWith(AUTH_PREFIX)) return pspRef.substring(AUTH_PREFIX.length());
        return pspRef;
    }
}
